package dev.novel.agent;

import dev.novel.agent.validation.PlanValidator;
import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.service.MemoryService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PlannerAgent implements NovelAgent {
    private final AgentContextService context;
    private final Store store;
    private final MemoryService memory;
    private final ModelClient model;
    private final Json json;
    private final PlanValidator validator;
    public PlannerAgent(AgentContextService context,Store store,MemoryService memory,ModelClient model,Json json,PlanValidator validator) {
        this.context=context;this.store=store;this.memory=memory;this.model=model;this.json=json;this.validator=validator;
    }
    public Set<String> tools() { return Set.of("read_plan","read_memory"); }
    public Set<String> kinds() { return Set.of("PLAN"); }
    public String purpose() { return "制定当前阶段的章节目标和冲突"; }
    public List<String> contextFiles() { return List.of("story_bible.md","outline.md","book_rules.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var novel=context.novel(task);
        int chapter=Store.integer(task,"chapter_no");
        var input=Map.of("title",novel.get("title"),"premise",novel.get("premise"),"bible",novel.get("bible"),
                "previousPlan",Store.string(novel,"plan"),"memory",memory.planningContext(novel),
                "recentSummaries",store.list("chapters.recent",novel.get("id")),"instructions",json.read(Store.string(task,"payload")));
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "制定持续连载小说的当前阶段大纲，每章参考2000至2500字。"+
                "输出 {stageGoal:string,coreMilestones:[string],chapters:[{number:int,title:string,goal:string,characters:[string],foreshadowIds:[string],sceneType:string,climax:boolean}]}。"+
                "chapters数量必须为"+novel.get("stage_size")+"，编号从"+chapter+"连续开始。每章明确行动目标、阻碍、结果和代价，保持人物及世界规则一致。"+
                "只描述故事内容，不写审批状态或执行说明。资料="+context.pack(task,input,""));
        validator.validate(result,chapter,Store.integer(novel,"stage_size"));
        return result;
    }
}
