package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.service.MemoryService;
import dev.novel.service.PlanChangeService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PlanChangeAgent implements NovelAgent {
    public Set<String> tools() { return Set.of("read_plan","read_memory"); }
    private final PlanChangeService changes;
    private final ModelClient model;
    private final MemoryService memory;
    private final Store store;
    private final Json json;
    private final AgentContextService context;
    public PlanChangeAgent(PlanChangeService changes,ModelClient model,MemoryService memory,Store store,Json json,AgentContextService context) {
        this.changes=changes;this.model=model;this.memory=memory;this.store=store;this.json=json;
        this.context=context;
    }
    public Set<String> kinds() { return Set.of("PLAN_CHANGE"); }
    public String purpose() { return "生成局部大纲修改建议与关联影响，等待人工确认"; }
    public List<String> contextFiles() { return List.of("outline.md","current_state.md","pending_hooks.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var change=changes.input(json.read(Store.string(task,"payload")).path("changeId").asText());
        var novel=store.novel(Store.string(task,"novel_id"));
        var result=model.call(task,AgentInstructions.SYSTEM+"你负责局部大纲修改和影响分析。",
                "只修改 target_path 指定字段，replacement 类型必须与原字段一致，字符串或字符串数组。其他内容由程序保留。"+
                "输出 {replacement:指定字段的新值,reason:string,coreChange:boolean,impacts:[{chapter:正整数,reason:string}]}。"+
                "reason 解释具体变化；impacts 说明相关章节的人物动机、因果关系、伏笔铺垫与答案、结局影响。"+
                "重要人物转变、关键伏笔答案、核心剧情和阶段结局变化须标记 coreChange=true。已完成正文只分析影响，不声称已经修改。"+
                "修改资料="+context.pack(task,Map.of("change",change,"story_bible.md",Store.string(novel,"bible"),
                        "memory",memory.planningContext(novel),"recentSummaries",store.list("chapters.recent",novel.get("id"))),""));
        changes.validate(change,result);
        return result;
    }
}
