package dev.novel.agent.runtime;

import dev.novel.agent.AgentContextService;
import dev.novel.repository.Store;
import dev.novel.service.MemoryService;
import dev.novel.service.MemoryPromptProjection;
import dev.novel.support.EvidenceMatcher;
import dev.novel.support.Json;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class StoryReadTools {
    private final Map<String,Object> task;
    private final AgentContextService context;
    private final MemoryService memory;
    private final Store store;
    private final Json json;
    private final AtomicInteger uses=new AtomicInteger();
    private final AtomicReference<RuntimeException> error=new AtomicReference<>();
    private Map<String,Object> delegation;
    private final MemoryPromptProjection projection;
    private final int resultBytes;
    private int remainingBytes;
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json) {
        this(task,context,memory,store,json,12000);
    }
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json,int resultBytes) {
        this.task=task;this.context=context;this.memory=memory;this.store=store;this.json=json;
        this.projection=new MemoryPromptProjection(json);this.resultBytes=resultBytes;this.remainingBytes=resultBytes*2;
    }
    private synchronized String read(String name,Supplier<Object> action) {
        try {
            if(uses.incrementAndGet()>8) throw new IllegalStateException("单次 Agent 任务最多执行8次工具调用");
            var novel=context.novel(task);
            Object result=action.get();
            if(Store.number(novel,"memory_version")!=Store.number(context.novel(task),"memory_version")) throw new IllegalStateException("工具读取期间记忆版本已经变化");
            String response=json.write(result);
            int bytes=projection.bytes(response);
            if(bytes>availableBytes()) throw new IllegalStateException("工具"+name+"需要"+bytes+"字节，剩余预算"+availableBytes()+"字节；请使用更具体的关键词和分页参数");
            remainingBytes-=bytes;
            store.event(Store.string(task,"novel_id"),Store.string(task,"id"),"AGENT_TOOL_USED",json.write(Map.of("tool",name,"responseBytes",bytes,"remainingToolBytes",remainingBytes)),(String)task.get("execution_id"));
            return response;
        } catch(RuntimeException failure) { error.compareAndSet(null,failure);throw failure; }
    }
    public void check() { if(error.get()!=null) throw error.get(); }
    public Map<String,Object> delegation() { return delegation; }
    private int availableBytes() { return Math.min(resultBytes,remainingBytes); }
    public synchronized void reserveRequestSpace(int requestBytes,int maxContext) {
        // 根据上一份完整请求的实际大小收紧工具预算，额外保留调用参数与消息包装空间。
        remainingBytes=Math.min(remainingBytes,Math.max(0,maxContext-requestBytes-4096));
    }
    public String plan() { return plan(null); }
    @Tool(name="read_plan",description="读取阶段目标和指定章节计划，默认当前章；其他章节通过编号读取，只读",readOnly=true)
    public String plan(@ToolParam(name="chapter",required=false,description="章节编号，默认当前任务章节") Integer chapter) {
        return read("read_plan",()-> {
            String stored=Store.string(context.novel(task),"plan");
            if(stored.isBlank()) return Map.of("availableChapters",List.of(),"chapters",List.of());
            var plan=(com.fasterxml.jackson.databind.node.ObjectNode)json.read(stored).deepCopy();
            var selected=json.tree(new ArrayList<>());
            var available=new ArrayList<Integer>();
            int number=chapter==null?Store.integer(task,"chapter_no"):chapter;
            plan.path("chapters").forEach(item->{
                available.add(item.path("number").asInt());
                if(item.path("number").asInt()==number) ((com.fasterxml.jackson.databind.node.ArrayNode)selected).add(item);
            });
            plan.set("chapters",selected);plan.set("availableChapters",json.tree(available));
            return plan;
        });
    }
    public String memory() { return memory(null,null,null,null,null); }
    public String memory(@ToolParam(name="query",required=false,description="关键词或完整记忆key") String query,
            @ToolParam(name="offset",required=false,description="记录起点，默认0") Integer offset,
            @ToolParam(name="limit",required=false,description="每页1至10条，默认5") Integer limit,
            @ToolParam(name="field",required=false,description="可选文本JSON Pointer，如/detail或/evidence；query须为完整key") String field,
            @ToolParam(name="characterOffset",required=false,description="字段文本的字符起点，默认0") Integer characterOffset) {
        return read("read_memory",()->memory.recall(context.novel(task),Store.integer(task,"chapter_no"),
                Objects.toString(query,""),offset==null?0:offset,limit==null?5:limit,Objects.toString(field,""),
                characterOffset==null?0:characterOffset,availableBytes()/2));
    }
    @Tool(name="list_memory_files",description="按主题目录分页列出当前版本的 Markdown 记忆文件，返回路径、标题、描述和指纹。",readOnly=true)
    public String files(@ToolParam(name="directory",required=false,description="主题目录，例如 characters、hooks，省略表示全部") String directory,
            @ToolParam(name="offset",required=false,description="起点，默认0") Integer offset,
            @ToolParam(name="limit",required=false,description="每页1至10个，默认5") Integer limit) {
        return read("list_memory_files",()->memory.files().list(Store.string(task,"novel_id"),Store.number(task,"memory_version"),
                Objects.toString(directory,""),offset==null?0:offset,limit==null?5:Math.min(limit,10)));
    }
    @Tool(name="search_memory",description="在固定版本的记忆文件中搜索标识、姓名、别名或关键词，返回有限命中上下文与分页位置。没有命中不能证明事实不存在。",readOnly=true)
    public String search(@ToolParam(name="query",description="关键词，最多200字符") String query,
            @ToolParam(name="directory",required=false,description="主题目录，默认全部") String directory,
            @ToolParam(name="offset",required=false,description="起点，默认0") Integer offset,
            @ToolParam(name="limit",required=false,description="每页1至10个，默认5") Integer limit) {
        return read("search_memory",()->memory.files().search(Store.string(task,"novel_id"),Store.number(task,"memory_version"),query,
                Objects.toString(directory,""),offset==null?0:offset,limit==null?5:limit,availableBytes()));
    }
    @Tool(name="read_memory",description="根据逻辑路径分页读取 Markdown 记忆文件。返回原文、指纹和 nextOffset。准备修改时沿用返回的 hash。",readOnly=true)
    public String file(@ToolParam(name="path",description="目录返回的完整逻辑路径，例如 characters/char_001.md") String path,
            @ToolParam(name="offset",required=false,description="Unicode code point 起点，默认0") Integer offset,
            @ToolParam(name="length",required=false,description="读取字符数1至4000，默认1500") Integer length) {
        return read("read_memory",()->memory.files().read(Store.string(task,"novel_id"),Store.number(task,"memory_version"),path,
                offset==null?0:offset,length==null?1500:length,availableBytes()));
    }
    @Tool(name="read_candidate_memory",description="按路径读取本次待审 Markdown 文件，用于跨文件核对或修复；不会发布修改。",readOnly=true)
    public String candidate(@ToolParam(name="path",description="候选文件逻辑路径") String path,
            @ToolParam(name="offset",required=false,description="字符起点，默认0") Integer offset) {
        return read("read_candidate_memory",()-> {
            var payload=json.read(Store.string(task,"payload"));var delta=payload.has("delta")?payload.path("delta"):payload;
            for(var item:delta.path("files")) if(path.equals(item.path("path").asText())) {
                String text=item.path("content").asText();int start=offset==null?0:offset,total=text.codePointCount(0,text.length());
                if(start<0 || start>total) throw new IllegalArgumentException("候选文件读取范围无效");
                int end=Math.min(total,start+Math.max(1,availableBytes()/8));
                return Map.of("path",path,"content",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)),"nextOffset",end,"hasMore",end<total);
            }
            throw new IllegalArgumentException("候选记忆文件不存在："+path);
        });
    }
    @Tool(name="read_source",description="按章节、revision和正文片段标识读取当前或历史原文，用于核对记忆来源。",readOnly=true)
    public String source(@ToolParam(name="chapter",description="来源章节编号") Integer chapter,
            @ToolParam(name="revision",description="正文 revision") Integer revision,
            @ToolParam(name="paragraph",description="正文片段标识，例如 p0") String paragraph,
            @ToolParam(name="offset",required=false,description="字符起点，默认0") Integer offset) {
        return read("read_source",()-> {
            if(chapter==null || revision==null || chapter<1 || revision<0 || chapter>Store.integer(task,"chapter_no"))
                throw new IllegalArgumentException("来源章节或修订超出当前任务范围");
            var rows=store.list("chapters.source",task.get("novel_id"),chapter,revision);
            if(rows.isEmpty()) throw new IllegalArgumentException("来源正文 revision 不存在");
            String text=new EvidenceMatcher(json).paragraphs(Store.string(rows.get(0),"body")).get(paragraph);
            if(text==null) throw new IllegalArgumentException("来源正文片段不存在");
            int start=offset==null?0:offset,total=text.codePointCount(0,text.length());
            if(start<0 || start>total) throw new IllegalArgumentException("来源读取范围无效");
            int end=Math.min(total,start+Math.max(1,availableBytes()/8));
            return Map.of("chapter",chapter,"revision",revision,"paragraph",paragraph,"text",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)),"nextOffset",end,"hasMore",end<total);
        });
    }
    public String relations(String character) { return relations(character,null,null); }
    @Tool(name="read_relations",description="分页查询某个人物的两跳关系，不重复返回证据原文，只读",readOnly=true)
    public String relations(@ToolParam(name="character",description="人物姓名") String character,
            @ToolParam(name="offset",required=false,description="关系起点，默认0") Integer offset,
            @ToolParam(name="limit",required=false,description="每页1至20条，默认10") Integer limit) {
        return read("read_relations",()-> {
            if(character==null || character.isBlank()) throw new IllegalArgumentException("请提供人物姓名");
            int start=offset==null?0:offset,count=limit==null?10:limit;
            if(start<0 || count<1 || count>20) throw new IllegalArgumentException("关系分页参数无效");
            var graph=projection.graph(memory.graph(Store.string(task,"novel_id"),Store.number(task,"memory_version"),List.of(character),2));
            var all=json.tree(graph.get("edges"));
            var edges=new ArrayList<Object>();
            var result=new LinkedHashMap<String,Object>();
            result.put("version",task.get("memory_version"));result.put("character",character);result.put("edges",edges);
            result.put("totalEdges",all.size());
            int next=Math.min(start,all.size());
            for(;next<all.size() && edges.size()<count;next++) {
                edges.add(all.get(next));
                if(projection.bytes(result)>availableBytes()/2-512) { edges.remove(edges.size()-1);break; }
            }
            result.put("nextOffset",next);result.put("hasMore",next<all.size());
            if(edges.isEmpty() && next<all.size()) {
                var edge=all.get(next);
                result.put("requiresFieldRead","relation:"+edge.path("subject").asText()+":"+edge.path("predicate").asText()+":"+edge.path("object").asText());
            }
            return result;
        });
    }
    @Tool(name="read_paragraph",description="按 p0 等段落编号读取当前正文原文，只读",readOnly=true)
    public String paragraph(@ToolParam(name="paragraph",description="段落编号，例如 p0") String paragraph) {
        return read("read_paragraph",()-> {
            var paragraphs=new EvidenceMatcher(json).paragraphs(Store.string(context.draft(task),"body"));
            if(!paragraphs.containsKey(paragraph)) throw new IllegalArgumentException("正文段落不存在");
            return Map.of("paragraph",paragraph,"text",paragraphs.get(paragraph));
        });
    }
    @Tool(name="delegate_draft",description="选择正文修改 Agent，创建待人工确认的委派建议")
    public String draft(@ToolParam(name="instructions",description="正文修改要求") String instructions) { return choose("DRAFT","",instructions); }
    @Tool(name="delegate_outline",description="选择局部大纲修改 Agent，创建待人工确认的委派建议")
    public String outline(@ToolParam(name="path",description="允许的大纲字段路径") String path,@ToolParam(name="instructions",description="局部修改要求") String instructions) { return choose("OUTLINE",path,instructions); }
    @Tool(name="delegate_memory",description="选择记忆修正 Agent，创建待人工确认的委派建议")
    public String repair(@ToolParam(name="instructions",description="记忆修正要求") String instructions) { return choose("MEMORY","",instructions); }
    private synchronized String choose(String target,String path,String instructions) {
        return read("delegate_"+switch(target) {case "DRAFT"->"draft";case "MEMORY"->"memory";default->"outline";},()-> {
            if(delegation!=null) throw new IllegalStateException("一次协调请求只能选择一个处理角色");
            if(instructions==null || instructions.isBlank() || instructions.length()>12000) throw new IllegalArgumentException("委派修改要求无效");
            delegation=Map.of("target",target,"path",path,"instructions",instructions);
            return Map.of("selection",delegation,"status","等待人工确认，尚未修改正文、大纲或记忆");
        });
    }
}
