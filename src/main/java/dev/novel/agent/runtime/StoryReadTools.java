package dev.novel.agent.runtime;

import dev.novel.agent.AgentContextService;
import dev.novel.repository.Store;
import dev.novel.service.MemoryService;
import dev.novel.support.EvidenceMatcher;
import dev.novel.support.Json;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class StoryReadTools {
    private final Map<String,Object> task;
    private final AgentContextService context;
    private final MemoryService memory;
    private final Store store;
    private final Json json;
    private final int resultBytes,totalBytes;
    private int remaining;
    private final AtomicReference<RuntimeException> error=new AtomicReference<>();
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json) {
        this(task,context,memory,store,json,24000,60000,13);
    }
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json,int resultBytes,int totalBytes,int maxRequests) {
        if(resultBytes<1024 || totalBytes<resultBytes) throw new IllegalArgumentException("工具预算配置无效");
        this.task=task;this.context=context;this.memory=memory;this.store=store;this.json=json;
        this.resultBytes=resultBytes;this.totalBytes=totalBytes;this.remaining=totalBytes;
    }
    public synchronized void reserveRequestSpace(int bytes,int maxContext) { remaining=Math.min(totalBytes,Math.max(0,maxContext-bytes-4096)); }
    public void check() { if(error.get()!=null) throw error.get(); }
    private int available() { return Math.max(0,Math.min(resultBytes,remaining)-512); }
    private synchronized String read(String name,Supplier<Object> action) {
        try {
            context.novel(task);
            if(available()<512) return json.write(Map.of("status","BUDGET_EXHAUSTED","instruction","缩小查询范围或整理上下文后继续。"));
            Object value=action.get();context.novel(task);
            String result=json.write(value);int bytes=bytes(result);
            if(bytes>Math.min(resultBytes,remaining)) return json.write(Map.of("status","BUDGET_EXHAUSTED","instruction","返回内容超过预算，请缩小limit或length。"));
            remaining-=bytes;
            store.event(Store.string(task,"novel_id"),Store.string(task,"id"),"AGENT_TOOL_USED",
                    json.write(Map.of("tool",name,"responseBytes",bytes,"remainingToolBytes",remaining)),(String)task.get("execution_id"));
            return result;
        } catch(RuntimeException failure) { error.compareAndSet(null,failure);throw failure; }
    }
    private static int bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
    @Tool(name="read_memory",description="按真实路径分页读取 Markdown，返回内容、指纹和继续位置。",readOnly=true)
    public String file(@ToolParam(name="path",description="目录或搜索返回的路径") String path,
            @ToolParam(name="offset",required=false,description="字符起点") Integer offset,
            @ToolParam(name="length",required=false,description="读取字符数，默认6000") Integer length) {
        return read("read_memory",()->memory.files().read(Store.string(task,"novel_id"),Store.number(task,"memory_version"),path,offset==null?0:offset,length==null?6000:length,available()));
    }
    @Tool(name="list_memory_files",description="分页列出主题文件路径与说明。",readOnly=true)
    public String files(@ToolParam(name="directory",required=false,description="主题目录") String directory,
            @ToolParam(name="offset",required=false,description="起点") Integer offset,
            @ToolParam(name="limit",required=false,description="每页数量") Integer limit) {
        return read("list_memory_files",()->memory.files().list(Store.string(task,"novel_id"),Store.number(task,"memory_version"),Objects.toString(directory,""),offset==null?0:offset,limit==null?15:limit));
    }
    @Tool(name="search_memory",description="按关键词搜索正式 Markdown 文件，返回路径与有限原文。",readOnly=true)
    public String search(@ToolParam(name="query",description="关键词") String query,
            @ToolParam(name="directory",required=false,description="主题目录") String directory,
            @ToolParam(name="offset",required=false,description="起点") Integer offset,
            @ToolParam(name="limit",required=false,description="每页数量") Integer limit) {
        return read("search_memory",()->memory.files().search(Store.string(task,"novel_id"),Store.number(task,"memory_version"),query,Objects.toString(directory,""),offset==null?0:offset,limit==null?10:limit,available()));
    }
    @Tool(name="read_memory_batch",description="按真实路径批量读取文件，在共同预算中返回继续位置。",readOnly=true)
    public String batch(@ToolParam(name="paths",description="路径列表") List<String> paths,
            @ToolParam(name="offset",required=false,description="字符起点") Integer offset,
            @ToolParam(name="length",required=false,description="每个文件读取字符数") Integer length) {
        return read("read_memory_batch",()->memory.files().batch(Store.string(task,"novel_id"),Store.number(task,"memory_version"),paths,offset==null?0:offset,length==null?6000:length,available()));
    }
    @Tool(name="read_plan",description="读取当前阶段批准的大纲。",readOnly=true)
    public String plan(@ToolParam(name="chapter",required=false,description="可选章节编号") Integer chapter) {
        return read("read_plan",()-> {
            String stored=Store.string(context.novel(task),"plan");
            var plan=json.read(stored.isBlank()?"{}":stored);
            if(chapter==null) return plan;
            for(var item:plan.path("chapters")) if(item.path("number").asInt()==chapter) return item;
            throw new IllegalArgumentException("章节计划不存在");
        });
    }
    @Tool(name="read_paragraph",description="按p0等编号读取当前正文。",readOnly=true)
    public String paragraph(@ToolParam(name="paragraph",description="正文编号") String paragraph) {
        return read("read_paragraph",()-> {
            var text=new EvidenceMatcher(json).paragraphs(Store.string(context.draft(task),"body")).get(paragraph);
            if(text==null) throw new IllegalArgumentException("正文片段不存在");
            return Map.of("paragraph",paragraph,"text",text);
        });
    }
    @Tool(name="read_source",description="读取指定章节和修订的原文片段。",readOnly=true)
    public String source(@ToolParam(name="chapter",description="章节编号") Integer chapter,
            @ToolParam(name="revision",description="修订编号") Integer revision,
            @ToolParam(name="paragraph",description="片段编号") String paragraph,
            @ToolParam(name="offset",required=false,description="字符起点") Integer offset) {
        return read("read_source",()-> {
            if(chapter==null || revision==null || chapter<1 || revision<0 || chapter>Store.integer(task,"chapter_no")) throw new IllegalArgumentException("来源超出任务范围");
            var rows=store.list("chapters.source",task.get("novel_id"),chapter,revision);
            if(rows.isEmpty()) throw new IllegalArgumentException("来源正文不存在");
            String text=new EvidenceMatcher(json).paragraphs(Store.string(rows.get(0),"body")).get(paragraph);
            if(text==null) throw new IllegalArgumentException("来源正文片段不存在");
            int start=offset==null?0:offset,total=text.codePointCount(0,text.length());
            if(start<0 || start>total) throw new IllegalArgumentException("来源读取范围无效");
            int end=Math.min(total,start+available()/8);
            return Map.of("text",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)),"nextOffset",end,"hasMore",end<total);
        });
    }
}
