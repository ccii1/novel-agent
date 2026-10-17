package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.integration.model.ModelClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service
public class ResearchService {
    private final Store store;
    private final Json json;
    private final ModelClient model;
    public ResearchService(Store store,Json json,ModelClient model) { this.store=store;this.json=json;this.model=model; }
    public String create(List<String> urls,String brief) {
        var links=urls==null?List.<String>of():List.copyOf(urls);
        String instruction=Objects.toString(brief,"").trim();
        if(links.size()>5 || instruction.length()>4000 || (links.isEmpty() && instruction.isBlank()))
            throw new IllegalArgumentException("请填写自己的想法或参考链接，想法最多4000字符，链接最多五个");
        links.forEach(this::validateUrl);
        return store.transaction(()->store.enqueue(null,"RESEARCH",0,0,0,"research:"+UUID.randomUUID(),Map.of("urls",links,"brief",instruction)));
    }
    public Map<String,Object> detail(String id) {
        return Map.of("report",store.one("SELECT * FROM research WHERE id=?",id),
                "messages",store.list("SELECT * FROM research_messages WHERE research_id=? ORDER BY id",id),
                "tasks",store.list("SELECT * FROM tasks WHERE research_id=? ORDER BY created_at DESC",id));
    }
    public String chat(String id,String message) {
        if(message==null || message.isBlank() || message.length()>4000) throw new IllegalArgumentException("讨论内容需要为1至4000字符");
        return store.transaction(()-> {
            var report=store.one("SELECT * FROM research WHERE id=? FOR UPDATE",id);
            if(!store.list("SELECT id FROM tasks WHERE research_id=? AND status IN ('WAITING','RUNNING','READY','ERROR')",id).isEmpty())
                throw new IllegalStateException("请等待当前回复完成；失败任务需要处理后继续讨论");
            var history=new ArrayList<>(store.list("SELECT role,content FROM research_messages WHERE research_id=? ORDER BY id DESC LIMIT 12",id));
            Collections.reverse(history);
            String task=store.enqueue(null,"RESEARCH_CHAT",0,0,0,"research-chat:"+UUID.randomUUID(),
                    Map.of("researchId",id,"message",message,"history",history,"previousIdeas",json.read((String)report.get("ideas")),
                            "evidence",json.read((String)report.get("sources")),"brief",Store.string(report,"brief")));
            store.update("UPDATE tasks SET research_id=? WHERE id=?",id,task);
            store.update("INSERT INTO research_messages(research_id,role,content,task_id) VALUES(?,'USER',?,?)",id,message,task);
            return task;
        });
    }
    public JsonNode execute(Map<String,Object> task,JsonNode payload,String system) throws Exception {
        JsonNode sources=payload.path("evidence");
        boolean discussion="RESEARCH_CHAT".equals(task.get("kind"));
        if(!sources.isArray()) {
            List<Map<String,String>> collected=new ArrayList<>();
            var urls=payload.path("urls");
            if(!urls.isArray() || urls.size()>5 || (urls.isEmpty() && payload.path("brief").asText().isBlank()))
                throw new IllegalArgumentException("请提供自己的想法或1至5个参考链接");
            for(var item:urls) {
                String url=item.asText();validateUrl(url);
                var response=Jsoup.connect(url).timeout(20000).maxBodySize(2_000_000).followRedirects(false).execute();
                if(response.statusCode()!=200) throw new IllegalStateException("资料页面返回 HTTP "+response.statusCode());
                var doc=response.parse();doc.select("script,style,nav,footer").remove();
                String text=doc.body().text();
                if(text.length()<200) throw new IllegalStateException("页面可读取资料不足，请提供具体榜单或作品详情页面");
                collected.add(Map.of("url",url,"title",doc.title(),"retrievedAt",Instant.now().toString(),"text",text.substring(0,Math.min(text.length(),10000))));
            }
            ObjectNode updated=payload.deepCopy();updated.set("evidence",json.read(json.write(collected)));
            store.update("UPDATE tasks SET payload=? WHERE id=?",json.write(updated),task.get("id"));
            sources=updated.get("evidence");
        }
        String instruction="输出：{reply:string,ideas:[{title:string,audience:string,genre:string,premise:string,hook:string,longTermConflict:string,risks:[string],sources:[string]}]}。"+
                "给出三个完整原创长篇方案，尊重用户题材、人设和情节要求；未指定读者时兼顾男频与女频。"+
                "reply 用自然语言回应用户，解释方案取舍与本轮调整，最多2000字。不要沿用已有作品的具体人物、情节或文字。"+
                "sources 只能引用公开资料中的 URL；没有参考资料时 sources 必须为空数组，明确这是创意讨论，禁止编造榜单、热度、市场数据和收益。"+
                "用户想法="+payload.path("brief").asText()+"；公开资料="+json.write(sources);
        if(discussion) instruction+="；当前三个方案="+json.write(payload.path("previousIdeas"))+"；最近交流="+json.write(payload.path("history"))+
                "；本轮用户意见="+payload.path("message").asText()+"。具体回应本轮意见，保留用户认可的设定，返回更新后的三个完整方案。";
        var result=model.call(task,system+"你负责原创选题和持续讨论。",instruction);
        validateResult(result,sources);
        return json.read(json.write(Map.of("sources",sources,"ideas",result.get("ideas"),"reply",result.get("reply"),"brief",payload.path("brief").asText())));
    }
    void validateResult(JsonNode result,JsonNode sources) {
        if(json.text(result,"reply").length()>6000) throw new IllegalArgumentException("选题回复超过长度限制");
        if(!result.path("ideas").isArray() || result.get("ideas").size()!=3) throw new IllegalArgumentException("选题结果必须包含3个创意");
        Set<String> urls=new HashSet<>();sources.forEach(source->urls.add(source.path("url").asText()));
        for(var idea:result.get("ideas")) {
            for(String field:List.of("title","premise","audience","genre","hook","longTermConflict")) json.text(idea,field);
            if(!idea.path("risks").isArray() || !idea.path("sources").isArray()) throw new IllegalArgumentException("选题缺少风险或来源列表");
            for(var source:idea.get("sources")) if(!urls.contains(source.asText())) throw new IllegalArgumentException("选题引用了未采集的资料地址");
        }
    }
    public void finished(Map<String,Object> task,JsonNode result) {
        String taskId=(String)task.get("id"),id;
        if("RESEARCH".equals(task.get("kind"))) {
            id=UUID.randomUUID().toString();
            store.update("INSERT INTO research(id,task_id,sources,ideas,brief) VALUES(?,?,?,?,?)",id,taskId,json.write(result.get("sources")),json.write(result.get("ideas")),result.path("brief").asText());
            String brief=result.path("brief").asText();
            store.update("INSERT INTO research_messages(research_id,role,content,task_id) VALUES(?,'USER',?,?)",id,brief.isBlank()?"根据提交的参考链接研究原创选题。":brief,taskId);
            store.update("UPDATE tasks SET research_id=? WHERE id=?",id,taskId);
        } else {
            id=Store.string(task,"research_id");
            store.one("SELECT id FROM research WHERE id=? FOR UPDATE",id);
            store.update("UPDATE research SET ideas=? WHERE id=?",json.write(result.get("ideas")),id);
        }
        store.update("INSERT INTO research_messages(research_id,role,content,ideas,task_id) VALUES(?,'ASSISTANT',?,?,?)",id,json.text(result,"reply"),json.write(result.get("ideas")),taskId);
    }
    private void validateUrl(String url) {
        URI uri=URI.create(url);
        if(!"https".equals(uri.getScheme()) || !"fanqienovel.com".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null)
            throw new IllegalArgumentException("仅接受 https://fanqienovel.com 的公开页面");
    }
}
