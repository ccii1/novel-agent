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
    public Set<String> tools() {
        return Set.of("read_plan", "read_memory");
    }

    private final AgentContextService context;
    private final Store store;
    private final MemoryService memory;
    private final ModelClient model;
    private final Json json;
    private final PlanValidator validator;
    private final dev.novel.service.PlanChangeService changes;

    public PlannerAgent(AgentContextService context, Store store, MemoryService memory, ModelClient model, Json json, PlanValidator validator, dev.novel.service.PlanChangeService changes) {
        this.context = context;
        this.store = store;
        this.memory = memory;
        this.model = model;
        this.json = json;
        this.validator = validator;
        this.changes = changes;
    }

    public Set<String> kinds() {
        return Set.of("PLAN", "PLAN_CHANGE");
    }

    public String purpose() {
        return "制定阶段冲突、关键节点和连续章节计划";
    }

    public List<String> contextFiles() {
        return List.of("story_bible.md", "outline.md", "book_rules.md");
    }

    public JsonNode execute(Map<String, Object> task) throws Exception {
        if ("PLAN_CHANGE".equals(task.get("kind"))) return changePlan(task);
        var novel = context.novel(task);
        int chapter = Store.integer(task, "chapter_no");
        var input = new LinkedHashMap<String, Object>();
        input.put("story_bible.md", Map.of("title", novel.get("title"), "premise", novel.get("premise"), "bible", novel.get("bible")));
        input.put("outline.md", Store.string(novel, "plan"));
        input.put("book_rules.md", Map.of("approval", "重大剧情变更等待人工批准"));
        input.put("memoryVersion", novel.get("memory_version"));
        input.put("memory", memory.planningContext(novel));
        input.put("recentSummaries", store.list("chapters.recent", novel.get("id")));
        input.put("instructions", json.read(Store.string(task, "payload")));
        var result = model.call(task, AgentInstructions.SYSTEM + "你负责阶段规划。",
                "制定持续连载小说的当前阶段大纲，无需安排整部作品完结。每章参考篇幅2000至2500字，按情节完整性允许浮动。" +
                        "输出：{stageGoal:string,coreMilestones:[string],chapters:[{number:int,title:string,goal:string,characters:[string],foreshadowIds:[string],sceneType:string,climax:boolean}]}。" +
                        "chapters 数量必须为" + novel.get("stage_size") + "，编号从" + chapter + "连续开始。设计冲突、失败代价、信息揭示、高潮与缓和。" +
                        "stageGoal 和章节目标只描述故事内容，不写审批状态或执行说明；审批状态由程序维护。重大剧情变更等待人工审核。规划资料=" + context.pack(task, input, ""));
        validator.validate(result, chapter, Store.integer(novel, "stage_size"));
        return result;
    }

    private JsonNode changePlan(Map<String, Object> task) throws Exception {
        var change = changes.input(json.read(Store.string(task, "payload")).path("changeId").asText());
        var novel = context.novel(task);
        var result = model.call(task, AgentInstructions.SYSTEM + "你负责局部大纲修改和影响分析。",
                "只修改 target_path 指定字段，replacement 类型必须与原字段一致，字符串或字符串数组。其他内容由程序保留。" +
                        "输出 {replacement:指定字段的新值,reason:string,coreChange:boolean,impacts:[{chapter:正整数,reason:string}]}。" +
                        "reason解释具体变化；impacts说明相关章节的人物动机、因果关系、伏笔与结局影响。" +
                        "重要人物转变、关键伏笔答案、核心剧情和阶段结局变化须标记coreChange=true。已完成正文只分析影响。" +
                        "修改资料=" + context.pack(task, Map.of("change", change, "story_bible.md", Store.string(novel, "bible"),
                        "memory", memory.planningContext(novel), "recentSummaries", store.list("chapters.recent", novel.get("id"))), ""));
        changes.validate(change, result);
        return result;
    }
}
