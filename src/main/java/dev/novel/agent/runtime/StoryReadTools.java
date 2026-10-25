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
    private int requests;
    private final int maxRequests;
    private final int contextResultBytes;
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json) {
        this(task,context,memory,store,json,24000,60000,4);
    }
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json,int resultBytes) {
        this(task,context,memory,store,json,resultBytes,resultBytes*2,4);
    }
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json,int resultBytes,int totalBytes,int maxRequests) {
        if(resultBytes<1024 || totalBytes<resultBytes || maxRequests<1) throw new IllegalArgumentException("工具预算配置无效");
        this.task=task;this.context=context;this.memory=memory;this.store=store;this.json=json;
        this.projection=new MemoryPromptProjection(json);this.resultBytes=resultBytes;this.remainingBytes=totalBytes;this.maxRequests=maxRequests;this.contextResultBytes=totalBytes;
    }
    private synchronized String read(String name,Supplier<Object> action) {
        try {
            int callNumber=uses.incrementAndGet();
            if(availableBytes()<512) return json.write(Map.of("status","BUDGET_EXHAUSTED","tool",name,
                    "instruction","当前请求没有足够工具返回空间。根据已核对资料完成任务，必要资料不足时明确指出；后续模型请求将检查是否需要压缩。"));
            var novel=context.novel(task);
            Object result=action.get();
            if(Store.number(novel,"memory_version")!=Store.number(context.novel(task),"memory_version")) throw new IllegalStateException("工具读取期间记忆版本已经变化");
            var payload=(com.fasterxml.jackson.databind.node.ObjectNode)json.tree(result);
            var limits=payload.putObject("_toolBudget");
            limits.put("toolCall",callNumber);limits.put("remainingModelRequests",Math.max(0,maxRequests-requests));
            limits.put("resultLimitBytes",resultBytes);limits.put("remainingBytes",remainingBytes);
            int bytes;
            String response;
            // 预算信息本身也计入返回量，报告值保持在实际剩余空间以内。
            do {
                response=json.write(payload);bytes=projection.bytes(response);
                int remaining=Math.max(0,remainingBytes-bytes);
                if(limits.path("remainingBytes").asInt()<=remaining) break;
                limits.put("remainingBytes",remaining);
            } while(true);
            int limit=Math.min(resultBytes,remainingBytes);
            if(bytes>limit) {
                response=json.write(Map.of("status","BUDGET_EXHAUSTED","tool",name,"requiredBytes",bytes,"availableBytes",limit,
                        "instruction","本次查询返回过多，请缩小 limit、length 或查询范围；原文需要继续核对，不能推断未读内容。"));
                bytes=projection.bytes(response);
            }
            remainingBytes-=bytes;
            store.event(Store.string(task,"novel_id"),Store.string(task,"id"),"AGENT_TOOL_USED",json.write(Map.of("tool",name,"toolCall",callNumber,"responseBytes",bytes,"remainingToolBytes",remainingBytes)),(String)task.get("execution_id"));
            return response;
        } catch(RuntimeException failure) { error.compareAndSet(null,failure);throw failure; }
    }
    public void check() { if(error.get()!=null) throw error.get(); }
    public Map<String,Object> delegation() { return delegation; }
    private int availableBytes() { return Math.max(0,Math.min(resultBytes,remainingBytes)-512); }
    public synchronized void reserveRequestSpace(int requestBytes,int maxContext) {
        // 根据上一份完整请求的实际大小收紧工具预算，额外保留调用参数与消息包装空间。
        remainingBytes=Math.min(contextResultBytes,Math.max(0,maxContext-requestBytes-4096));
        requests++;
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
            @ToolParam(name="limit",required=false,description="每页1至30个，默认15") Integer limit) {
        return read("list_memory_files",()->memory.files().list(Store.string(task,"novel_id"),Store.number(task,"memory_version"),
                Objects.toString(directory,""),offset==null?0:offset,limit==null?15:limit));
    }
    @Tool(name="search_memory",description="在固定版本的记忆文件中搜索标识、姓名、别名或关键词，返回有限命中上下文与分页位置。没有命中不能证明事实不存在。",readOnly=true)
    public String search(@ToolParam(name="query",description="关键词，最多200字符") String query,
            @ToolParam(name="directory",required=false,description="主题目录，默认全部") String directory,
            @ToolParam(name="offset",required=false,description="起点，默认0") Integer offset,
            @ToolParam(name="limit",required=false,description="每页1至20个，默认10") Integer limit) {
        return read("search_memory",()->memory.files().search(Store.string(task,"novel_id"),Store.number(task,"memory_version"),query,
                Objects.toString(directory,""),offset==null?0:offset,limit==null?10:limit,availableBytes()));
    }
    @Tool(name="lookup_memory",description="按稳定标识或名称定位正式记忆文件，返回 FOUND、NOT_FOUND 或 AMBIGUOUS 和真实路径，不需要猜测文件名。",readOnly=true)
    public String lookup(@ToolParam(name="entity",description="上下文提供的稳定标识或人物、伏笔、设定名称") String entity) {
        return read("lookup_memory",()->memory.files().lookup(Store.string(task,"novel_id"),Store.number(task,"memory_version"),entity));
    }
    @Tool(name="read_memory",description="根据逻辑路径分页读取 Markdown 记忆文件。返回原文、指纹和 nextOffset。准备修改时沿用返回的 hash。",readOnly=true)
    public String file(@ToolParam(name="path",description="目录、搜索结果或上下文返回的完整逻辑路径") String path,
            @ToolParam(name="offset",required=false,description="Unicode code point 起点，默认0") Integer offset,
            @ToolParam(name="length",required=false,description="读取字符数1至12000，默认6000") Integer length) {
        return read("read_memory",()->memory.files().read(Store.string(task,"novel_id"),Store.number(task,"memory_version"),path,
                offset==null?0:offset,length==null?6000:length,availableBytes()));
    }
    @Tool(name="read_memory_batch",description="批量读取目录、搜索或上下文提供的路径，共用字节预算；missingPaths 列出当前版本不存在的路径。",readOnly=true)
    public String batch(@ToolParam(name="paths",description="目录或上下文提供的逻辑路径列表，最多30个，顺序表示优先级") List<String> paths,
            @ToolParam(name="offset",required=false,description="各文件的 Unicode 起点，默认0；单独续读时使用 read_memory") Integer offset,
            @ToolParam(name="length",required=false,description="每份文件读取1至12000个字符，默认6000") Integer length) {
        return read("read_memory_batch",()->memory.files().batch(Store.string(task,"novel_id"),Store.number(task,"memory_version"),paths,
                offset==null?0:offset,length==null?6000:length,availableBytes()));
    }
    @Tool(name="read_candidate_memory",description="按路径读取本次待审 Markdown 文件，用于跨文件核对或修复；不会发布修改。",readOnly=true)
    public String candidate(@ToolParam(name="path",description="候选文件逻辑路径") String path,
            @ToolParam(name="offset",required=false,description="字符起点，默认0") Integer offset) {
        return read("read_candidate_memory",()-> {
            memory.files().path(path);
            var payload=json.read(Store.string(task,"payload"));var delta=payload.has("delta")?payload.path("delta"):payload;
            for(var item:delta.path("files")) if(path.equals(item.path("path").asText())) {
                String text=item.path("content").asText();int start=offset==null?0:offset,total=text.codePointCount(0,text.length());
                if(start<0 || start>total) throw new IllegalArgumentException("候选文件读取范围无效");
                int end=Math.min(total,start+Math.max(1,availableBytes()/8));
                return Map.of("path",path,"content",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)),"nextOffset",end,"hasMore",end<total,
                        "scope","candidate","status","FOUND","candidate",true,"targetPolicy",memory.files().editingPolicy(Store.string(task,"novel_id"),Store.number(task,"memory_version"),path),
                        "instruction","此内容是未发布候选；targetPolicy.readOnly=true 时需要改写到普通主题目标。修正需返回完整候选集合，保留有效修改。");
            }
            return Map.of("status","NOT_FOUND","scope","candidate","memoryVersion",task.get("memory_version"),"path",path,
                    "instruction","当前候选集合没有此文件；正式版本使用 read_memory，候选路径从本任务提案获取。");
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
