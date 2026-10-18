package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.integration.model.ModelClient;
import dev.novel.integration.model.ModelCatalog;
import dev.novel.agent.AgentOutputReader;
import dev.novel.dto.AgentOutputs;

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
    private final AgentOutputReader outputs;
    private final ModelCatalog catalog;
    public ResearchService(Store store,Json json,ModelClient model,AgentOutputReader outputs,ModelCatalog catalog) { this.store=store;this.json=json;this.model=model;this.outputs=outputs;this.catalog=catalog; }
    public String create(List<String> urls,String brief) {
        return create(urls,brief,null,null);
    }
    public String create(List<String> urls,String brief,String provider,String chosenModel) {
        var links=urls==null?List.<String>of():List.copyOf(urls);
        String instruction=Objects.toString(brief,"").trim();
        if(links.size()>5 || instruction.length()>4000 || (links.isEmpty() && instruction.isBlank()))
            throw new IllegalArgumentException("请填写自己的想法或参考链接，想法最多4000字符，链接最多五个");
        links.forEach(this::validateUrl);
        var selected=selection(provider,chosenModel,"RESEARCH");
        return store.transaction(()-> {
            String task=store.enqueue(null,"RESEARCH",0,0,0,"research:"+UUID.randomUUID(),Map.of("urls",links,"brief",instruction));
            store.update("tasks.modelChoice",selected.provider(),selected.model(),task);
            return task;
        });
    }
    public Map<String,Object> detail(String id) {
        return Map.of("report",store.one("research.find",id),
                "messages",store.list("research.messages",id),
                "tasks",store.list("tasks.byResearch",id),"modelChoice",reportChoice(id));
    }
    public String chat(String id,String message) {
        return chat(id,message,null,null);
    }
    public String chat(String id,String message,String provider,String chosenModel) {
        if(message==null || message.isBlank() || message.length()>4000) throw new IllegalArgumentException("讨论内容需要为1至4000字符");
        return store.transaction(()-> {
            var report=store.one("research.lock",id);
            if(!store.list("tasks.activeResearch",id).isEmpty())
                throw new IllegalStateException("请等待当前回复完成；失败任务需要处理后继续讨论");
            var choice=reportChoice(id);
            boolean inherit=(provider==null || provider.isBlank()) && (chosenModel==null || chosenModel.isBlank());
            var selected=selection(provider==null || provider.isBlank()?choice.get("provider"):provider,
                    inherit?choice.get("model"):chosenModel,"RESEARCH_CHAT");
            var history=new ArrayList<>(store.list("research.recent",id));
            Collections.reverse(history);
            String task=store.enqueue(null,"RESEARCH_CHAT",0,0,0,"research-chat:"+UUID.randomUUID(),
                    Map.of("researchId",id,"message",message,"history",history,"previousIdeas",json.read((String)report.get("ideas")),
                            "evidence",json.read((String)report.get("sources")),"brief",Store.string(report,"brief")));
            store.update("tasks.linkResearch",id,task);
            store.update("tasks.modelChoice",selected.provider(),selected.model(),task);
            store.update("research.userMessage",id,message,task);
            return task;
        });
    }
    private ModelCatalog.Selection selection(String provider,String chosenModel,String kind) {
        if(provider!=null && provider.length()>80 || chosenModel!=null && chosenModel.length()>200)
            throw new IllegalArgumentException("模型服务或模型名称超出长度限制");
        var selected=catalog.resolve(provider,chosenModel,kind);
        if((provider!=null && !provider.isBlank() || chosenModel!=null && !chosenModel.isBlank()) && !selected.ready())
            throw new IllegalArgumentException("所选模型服务尚未配置完成");
        return selected;
    }
    private Map<String,String> reportChoice(String id) {
        var rows=store.list("tasks.researchModel",id);
        var selected=rows.isEmpty()?catalog.resolve(null,"","RESEARCH_CHAT"):
                catalog.resolve(Store.string(rows.get(0),"provider"),Store.string(rows.get(0),"model_name"),"RESEARCH_CHAT");
        return Map.of("provider",selected.provider(),"model",selected.model());
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
            ObjectNode updated=payload.deepCopy();updated.set("evidence",json.tree(collected));
            store.update("tasks.payload",json.write(updated),task.get("id"));
            sources=updated.get("evidence");
        }
        String instruction="输出：{reply:string,ideas:[{title:string,audience:string,genre:string,premise:string,hook:string,longTermConflict:string,risks:[string],sources:[string]}]}。"+
                "给出三个完整原创长篇方案，尊重用户题材、人设和情节要求；未指定读者时兼顾男频与女频。"+
                "reply 用自然语言回应用户，解释方案取舍与本轮调整，最多2000字。不要沿用已有作品的具体人物、情节或文字。"+
                "sources 只能引用公开资料中的 URL；没有参考资料时 sources 必须为空数组，明确这是创意讨论，禁止编造榜单、热度、市场数据和收益。"+
                "用户想法="+payload.path("brief").asText()+"；公开资料="+json.write(sources);
        if(discussion) instruction+="；当前三个方案="+json.write(payload.path("previousIdeas"))+"；最近交流="+json.write(payload.path("history"))+
                "；本轮用户意见="+payload.path("message").asText()+"。具体回应本轮意见，保留用户认可的设定，返回更新后的三个完整方案。";
        store.event((String)task.get("novel_id"),Store.string(task,"id"),"RESEARCH_INPUT_READY",
                json.write(Map.of("sourceCount",sources.size(),"discussion",discussion)),(String)task.get("execution_id"));
        var result=model.call(task,system+"你负责原创选题和持续讨论。",instruction);
        validateResult(result,sources);
        store.event((String)task.get("novel_id"),Store.string(task,"id"),"RESEARCH_OUTPUT_VALIDATED",
                json.write(Map.of("ideaCount",result.path("ideas").size())),(String)task.get("execution_id"));
        return json.tree(Map.of("sources",sources,"ideas",result.get("ideas"),"reply",result.get("reply"),"brief",payload.path("brief").asText()));
    }
    void validateResult(JsonNode result,JsonNode sources) {
        var report=outputs.read(result,AgentOutputs.Research.class);
        Set<String> urls=new HashSet<>();sources.forEach(source->urls.add(source.path("url").asText()));
        for(var idea:report.ideas()) {
            for(String source:idea.sources()) if(!urls.contains(source)) throw new IllegalArgumentException("选题引用了未采集的资料地址");
        }
    }
    public void finished(Map<String,Object> task,JsonNode result) {
        String taskId=(String)task.get("id"),id;
        if("RESEARCH".equals(task.get("kind"))) {
            id=UUID.randomUUID().toString();
            store.update("research.create",id,taskId,json.write(result.get("sources")),json.write(result.get("ideas")),result.path("brief").asText());
            String brief=result.path("brief").asText();
            store.update("research.userMessage",id,brief.isBlank()?"根据提交的参考链接研究原创选题。":brief,taskId);
            store.update("tasks.linkResearch",id,taskId);
        } else {
            id=Store.string(task,"research_id");
            store.one("research.lock",id);
            store.update("research.ideas",json.write(result.get("ideas")),id);
        }
        store.update("research.reply",id,json.text(result,"reply"),json.write(result.get("ideas")),taskId);
    }
    private void validateUrl(String url) {
        URI uri=URI.create(url);
        if(!"https".equals(uri.getScheme()) || !"fanqienovel.com".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null)
            throw new IllegalArgumentException("仅接受 https://fanqienovel.com 的公开页面");
    }
}
