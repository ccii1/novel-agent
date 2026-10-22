package dev.novel.agent.runtime;

import dev.novel.agent.AgentContextService;
import dev.novel.repository.Store;
import dev.novel.service.MemoryService;
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
    public StoryReadTools(Map<String,Object> task,AgentContextService context,MemoryService memory,Store store,Json json) {
        this.task=task;this.context=context;this.memory=memory;this.store=store;this.json=json;
    }
    private String read(String name,Supplier<Object> action) {
        try {
            if(uses.incrementAndGet()>8) throw new IllegalStateException("单次 Agent 任务最多执行8次工具调用");
            var novel=context.novel(task);
            Object result=action.get();
            if(Store.number(novel,"memory_version")!=Store.number(context.novel(task),"memory_version")) throw new IllegalStateException("工具读取期间记忆版本已经变化");
            store.event(Store.string(task,"novel_id"),Store.string(task,"id"),"AGENT_TOOL_USED",json.write(Map.of("tool",name)),(String)task.get("execution_id"));
            return json.write(result);
        } catch(RuntimeException failure) { error.compareAndSet(null,failure);throw failure; }
    }
    public void check() { if(error.get()!=null) throw error.get(); }
    public Map<String,Object> delegation() { return delegation; }
    @Tool(name="read_plan",description="读取当前作品已经保存的阶段大纲，只读",readOnly=true)
    public String plan() { return read("read_plan",()->json.read(Store.string(context.novel(task),"plan"))); }
    @Tool(name="read_memory",description="读取当前章节相关的人物、世界规则与伏笔记忆，只读",readOnly=true)
    public String memory() {
        return read("read_memory",()-> {
            var data=memory.contextData(context.novel(task),Store.integer(task,"chapter_no"));
            return Map.of("memoryVersion",data.get("memoryVersion"),"facts",data.get("facts"),"omittedMatchingFacts",data.get("omittedMatchingFacts"));
        });
    }
    @Tool(name="read_relations",description="查询当前作品某个人物的两跳关系，只读",readOnly=true)
    public String relations(@ToolParam(name="character",description="人物姓名") String character) {
        return read("read_relations",()->memory.graph(Store.string(task,"novel_id"),Store.number(task,"memory_version"),List.of(character),2));
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
