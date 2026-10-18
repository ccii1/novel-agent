package dev.novel.agent;

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
    public Set<String> kinds() { return Set.of("PLAN"); }
    public String purpose() { return "制定阶段冲突、关键节点和连续章节计划"; }
    public List<String> contextFiles() { return List.of("story_bible.md","outline.md","book_rules.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var novel=context.novel(task);
        int chapter=Store.integer(task,"chapter_no");
        var input=new LinkedHashMap<String,Object>();
        input.put("story_bible.md",Map.of("title",novel.get("title"),"premise",novel.get("premise"),"bible",novel.get("bible")));
        input.put("outline.md",Store.string(novel,"plan"));
        input.put("book_rules.md",Map.of("approval","重大剧情变更等待人工批准"));
        input.put("memoryVersion",novel.get("memory_version"));
        input.put("memory",memory.planningContext(novel));
        input.put("recentSummaries",store.list("chapters.recent",novel.get("id")));
        input.put("instructions",json.read(Store.string(task,"payload")));
        var result=model.call(task,AgentInstructions.SYSTEM+"你负责阶段规划。",
                "制定持续连载小说的当前阶段大纲，无需安排整部作品完结。每章参考篇幅2000至2500字，按情节完整性允许浮动。"+
                "输出：{stageGoal:string,coreMilestones:[string],chapters:[{number:int,title:string,goal:string,characters:[string],foreshadowIds:[string],sceneType:string,climax:boolean}]}。"+
                "chapters 数量必须为"+novel.get("stage_size")+"，编号从"+chapter+"连续开始。设计冲突、失败代价、信息揭示、高潮与缓和。"+
                "重大剧情变更等待人工审核。规划资料="+json.write(input));
        validator.validate(result,chapter,Store.integer(novel,"stage_size"));
        return result;
    }
}
