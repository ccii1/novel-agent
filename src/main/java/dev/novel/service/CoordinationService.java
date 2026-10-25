package dev.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CoordinationService {
    private final Store store;
    private final Json json;
    private final ProjectService projects;
    private final PlanChangeService plans;

    public CoordinationService(Store store, Json json, ProjectService projects, PlanChangeService plans) {
        this.store = store;
        this.json = json;
        this.projects = projects;
        this.plans = plans;
    }

    public List<Map<String, Object>> list(String id) {
        store.novel(id);
        return store.list("tasks.coordination", id);
    }

    public String request(String id, String instructions) {
        if (instructions == null || instructions.isBlank() || instructions.length() > 12000)
            throw new IllegalArgumentException("请填写修改要求，最多12000字符");
        return store.transaction(() -> {
            var novel = store.lockNovel(id);
            available(novel);
            if (Store.string(novel, "plan").isBlank()) throw new IllegalStateException("请等待阶段大纲生成");
            var payload = new LinkedHashMap<String, Object>();
            payload.put("instructions", instructions);
            payload.put("basePlan", json.read(Store.string(novel, "plan")));
            payload.put("stage", novel.get("stage_no"));
            payload.put("draftRevision", revision(novel));
            return store.enqueue(id, "COORDINATE", Store.integer(novel, "chapter_no"), 0, Store.number(novel, "memory_version"), id + ":coordinate:" + UUID.randomUUID(), payload);
        });
    }

    public Map<String, Object> options(String id) {
        var novel = store.novel(id);
        var result = new LinkedHashMap<String, Object>();
        result.put("outlineTargets", plans.targets(json.read(Store.string(novel, "plan"))));
        boolean memory = Boolean.TRUE.equals(projects.memoryReview(id).get("pending"));
        result.put("memoryRepairAllowed", memory);
        result.put("draftRevisionAllowed", !memory && Set.of("AWAITING_CHANGE", "AWAITING_HELP").contains(novel.get("status")));
        return result;
    }

    public void validate(String id, JsonNode result) {
        String target = result.path("target").asText(), path = result.path("path").asText();
        var choices = options(id);
        switch (target) {
            case "OUTLINE" -> {
                if (plans.targets(json.read(Store.string(store.novel(id), "plan"))).stream().noneMatch(item -> item.get("path").equals(path)))
                    throw new IllegalArgumentException("委派的大纲范围无效");
            }
            case "MEMORY" -> {
                if (!Boolean.TRUE.equals(choices.get("memoryRepairAllowed")))
                    throw new IllegalStateException("当前没有待处理的记忆审核");
            }
            case "DRAFT" -> {
                if (!Boolean.TRUE.equals(choices.get("draftRevisionAllowed")))
                    throw new IllegalStateException("当前不处于正文修改审核阶段");
            }
            default -> throw new IllegalArgumentException("协调 Agent 选择了未知的处理角色");
        }
    }

    public String approve(String id, String taskId) {
        return store.transaction(() -> {
            var novel = store.lockNovel(id);
            available(novel);
            var task = store.one("tasks.lock", taskId);
            if (!id.equals(task.get("novel_id")) || !"COORDINATE".equals(task.get("kind")) || !"DONE".equals(task.get("status")))
                throw new IllegalStateException("协调建议尚未生成或不属于当前作品");
            var payload = (ObjectNode) json.read(Store.string(task, "payload"));
            if (payload.has("delegatedTaskId")) throw new IllegalStateException("该委派建议已经确认");
            // 人工阅读建议期间资料可能变化，确认时重新检查生成建议所用的版本。
            if (Store.number(task, "memory_version") != Store.number(novel, "memory_version") || Store.integer(task, "chapter_no") != Store.integer(novel, "chapter_no")
                    || payload.path("stage").asInt() != Store.integer(novel, "stage_no") || payload.path("draftRevision").asInt() != revision(novel)
                    || !payload.path("basePlan").equals(json.read(Store.string(novel, "plan"))))
                throw new IllegalStateException("大纲、正文或记忆已经变化，请重新分析修改要求");
            var route = json.read(Store.string(task, "result"));
            validate(id, route);
            String instructions = route.path("instructions").asText();
            String next = switch (route.path("target").asText()) {
                case "OUTLINE" ->
                        Store.string(store.one("planChanges.find", plans.request(id, route.path("path").asText(), instructions)), "task_id");
                case "MEMORY" ->
                        projects.repairMemory(id, projects.memoryReview(id).get("taskId").toString(), instructions, null);
                case "DRAFT" -> projects.revise(id, instructions, null, true);
                default -> throw new IllegalArgumentException("未知的处理角色");
            };
            payload.put("delegatedTaskId", next);
            store.update("tasks.payload", json.write(payload), taskId);
            store.event(id, taskId, "COORDINATION_APPROVED", json.write(Map.of("target", route.path("target").asText(), "taskId", next)));
            return next;
        });
    }

    private int revision(Map<String, Object> novel) {
        var drafts = store.list("chapters.find", novel.get("id"), novel.get("chapter_no"));
        return drafts.isEmpty() ? -1 : Store.integer(drafts.get(0), "revision");
    }

    private void available(Map<String, Object> novel) {
        if (!PlanChangeService.allowedStatus(novel.get("status")))
            throw new IllegalStateException("请在人工审核阶段或暂停后提交协调请求");
        if (!store.list("tasks.coordinationActive", novel.get("id")).isEmpty())
            throw new IllegalStateException("请等待执行中的任务和协调请求结束");
    }
}
