package dev.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.agent.orchestration.ReviewOrchestrator;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkflowService {
    public static final int MEMORY_REPAIR_LIMIT = 0;
    private static final int REVISION_LIMIT = 3;
    private static final Set<String> SUSPENDED = Set.of("PAUSED", "ERROR", "BUDGET_PAUSED");

    private final Store store;
    private final Json json;
    private final MemoryService memory;
    private final ChapterQualityService quality;
    private final ResearchService research;
    private final PlanChangeService planChanges;
    private final ReviewOrchestrator reviews;
    private final TaskScheduler scheduler;
    private final ObservationService observations;

    public WorkflowService(Store store, Json json, MemoryService memory, ChapterQualityService quality,
            ResearchService research, PlanChangeService planChanges, ReviewOrchestrator reviews, TaskScheduler scheduler,ObservationService observations) {
        this.store = store;
        this.json = json;
        this.memory = memory;
        this.quality = quality;
        this.research = research;
        this.planChanges = planChanges;
        this.reviews = reviews;
        this.scheduler = scheduler;
        this.observations=observations;
    }

    public String schedule(Map<String, Object> novel, String kind, int chapter, int revision, Object payload) {
        return scheduler.schedule(novel, kind, chapter, revision, payload);
    }

    public void finished(Map<String, Object> task, JsonNode result) {
        MemoryFileService.Prepared prepared=null;
        if(Set.of("MEMORY","MEMORY_REPAIR","CHECK_MEMORY").contains(task.get("kind"))) {
            var novel=store.novel(Store.string(task,"novel_id"));
            if(!SUSPENDED.contains(novel.get("status")) && Store.number(novel,"memory_version")==Store.number(task,"memory_version")
                    && "RUNNING".equals(store.one("tasks.find",task.get("id")).get("status"))) {
                var draft=store.one("chapters.find",task.get("novel_id"),task.get("chapter_no"));
                var delta=memory.validateDelta(result,Store.string(draft,"body"));
                if(Store.integer(draft,"revision")!=Store.integer(task,"revision")) throw new IllegalStateException("记忆修改对应的正文版本已经变化");
                if(delta.has("files")) prepared=memory.files().prepare(Store.string(task,"novel_id"),Store.integer(task,"chapter_no"),Store.integer(task,"revision"),
                        Store.number(task,"memory_version")+1,delta);
            }
        }
        var publication=prepared;
        store.transaction(() -> {
            finishWithinTransaction(task, result,publication);
            String status=Store.string(store.one("tasks.find",task.get("id")),"status");
            if(task.get("execution_id")!=null) observations.finish(task,status,result,null);
            return null;
        });
    }

    private void finishWithinTransaction(Map<String, Object> task, JsonNode result,MemoryFileService.Prepared prepared) {
        String taskId = Store.string(task, "id");
        String novelId = (String) task.get("novel_id");
        String kind = Store.string(task, "kind");
        // 固定加锁顺序，同一作品的完成结果在这里串行决定后续步骤。
        var novel = novelId == null ? null : store.lockNovel(novelId);
        var persisted = store.one("tasks.lock", taskId);
        if (!"RUNNING".equals(persisted.get("status"))) return;

        // 修改建议独立保存，正式变更在人工确认时再次检查来源版本。
        if (kind.equals("PLAN_CHANGE") || kind.equals("COORDINATE")) {
            if (kind.equals("PLAN_CHANGE")) planChanges.finished(task, result);
            markDone(task, result);
            return;
        }
        if (novel != null && SUSPENDED.contains(novel.get("status"))) {
            store.update("tasks.ready", json.write(result), taskId);
            return;
        }
        if (novel != null && Store.number(novel, "memory_version") != Store.number(task, "memory_version")) {
            store.update("tasks.stale", taskId);
            return;
        }

        markDone(task, result);
        switch (kind) {
            case "RESEARCH", "RESEARCH_CHAT" -> research.finished(task, result);
            case "PLAN" -> store.update("novels.awaitPlan", json.write(result), novelId);
            case "WRITE", "REWRITE" -> saveDraft(novel, task, result);
            case "CHECK_CHARACTER", "CHECK_PLOT", "CHECK_PACING", "CHECK_STYLE", "CHECK_CHAPTER" ->
                    finishChapterReview(novel, task, result);
            case "REFLECT" -> finishReflection(novel, task, result);
            case "MEMORY", "MEMORY_REPAIR", "CHECK_MEMORY" -> commitApprovedChapter(novel,task,result,prepared);
            default -> throw new IllegalArgumentException("未知流程步骤：" + kind);
        }
    }

    private void markDone(Map<String, Object> task, JsonNode result) {
        store.update("tasks.done", json.write(result), task.get("id"));
        store.event((String) task.get("novel_id"), Store.string(task, "id"), "DONE", Store.string(task, "kind"));
    }

    private void saveDraft(Map<String, Object> novel, Map<String, Object> task, JsonNode result) {
        String novelId = Store.string(novel, "id");
        int chapter = Store.integer(task, "chapter_no");
        int revision = Store.integer(task, "revision");
        var old = store.list("chapters.find", novelId, chapter);
        if (!old.isEmpty()) {
            var previous = old.get(0);
            store.update("chapters.history", UUID.randomUUID().toString(), novelId, chapter,
                    previous.get("revision"), previous.get("title"), previous.get("body"));
        }
        String body = json.text(result, "body");
        store.update("chapters.draft", novelId, chapter, novel.get("stage_no"), revision,
                json.text(result, "title"), body, novel.get("memory_version"));
        state(novelId, "CHECKING");
        quality.record(novelId, chapter, revision, body);
        ensureReviewTasks(novel, chapter, revision);
    }

    private void finishChapterReview(Map<String, Object> novel, Map<String, Object> task, JsonNode result) {
        String novelId = Store.string(novel, "id");
        String kind = Store.string(task, "kind");
        int chapter = Store.integer(task, "chapter_no");
        int revision = Store.integer(task, "revision");
        boolean approved = result.path("approved").asBoolean();
        store.event(novelId, Store.string(task, "id"), approved ? "REVIEW_APPROVED" : "REVIEW_REJECTED",
                approved ? "正文审核通过" : "正文审核未通过：" + result.path("issues").size() + "项问题");
        if (kind.equals("CHECK_PACING") || kind.equals("CHECK_CHAPTER")) {
            store.update("quality.pacing", json.write(result.path("narrative")), novelId, chapter, revision);
        }
        var round = reviews.collect(novel, chapter, revision);
        // 等待同一版本的全部审核结果，单个 Agent 完成不会提前推进章节。
        if (!round.ready()) return;
        if(round.approved()) {
            state(novelId,"EXTRACTING");
            schedule(novel,"MEMORY",chapter,revision,Map.of());
        } else {
            var reports=store.list("tasks.reviews",novelId,chapter,revision,novel.get("memory_version"));
            var instructions=new java.util.ArrayList<Object>();
            for(var report:reports) instructions.add(json.read(Store.string(report,"result")));
            finishReflection(novel,task,json.tree(Map.of("majorChange",round.majorChange(),
                    "planConflict",round.planConflict(),
                    "instructions","逐项处理有原文依据的审核问题，保留已通过内容。审核报告："+json.write(instructions),"conflicts",java.util.List.of())));
        }
    }

    private void finishReflection(Map<String, Object> novel, Map<String, Object> task, JsonNode result) {
        String novelId = Store.string(novel, "id");
        int chapter = Store.integer(task, "chapter_no");
        int revision = Store.integer(task, "revision");
        var round=reviews.collect(novel,chapter,revision);
        boolean major = result.path("majorChange").asBoolean() || round.majorChange();
        store.update("novels.reflection", json.write(result), novelId);
        if (major || result.path("planConflict").asBoolean() || round.planConflict()) {
            state(novelId, "AWAITING_CHANGE");
        } else if (revision >= REVISION_LIMIT) {
            state(novelId, "AWAITING_HELP");
        } else {
            state(novelId, "WRITING");
            schedule(novel, "REWRITE", chapter, revision + 1, Map.of());
        }
    }

    private void commitApprovedChapter(Map<String, Object> novel, Map<String, Object> task, JsonNode pending,MemoryFileService.Prepared prepared) {
        String novelId = Store.string(novel, "id");
        int chapter = Store.integer(task, "chapter_no");
        var draft = store.one("chapters.find", novelId, chapter);
        if (Store.integer(draft, "revision") != Store.integer(task, "revision")
                || Store.integer(novel, "chapter_no") != chapter) {
            throw new IllegalStateException("记忆修改对应的正文版本已经变化");
        }
        var delta = memory.validateDelta(pending, Store.string(draft, "body"));
        long nextVersion = Store.number(novel, "memory_version") + 1;
        // 本方法仍处于 finished 的事务内，记忆、章节、进度及下一任务共同提交。
        if(delta.has("files")) {
            if(prepared==null || prepared.base()!=Store.number(novel,"memory_version") || !prepared.delta().equals(json.write(delta)))
                throw new IllegalStateException("记忆文件尚未完成准备或与审核内容不同");
            memory.files().publish(prepared);
        } else memory.commit(novelId, chapter, nextVersion, delta);
        store.update("chapters.accept", json.text(delta, "summary"), nextVersion, novelId, chapter);
        var chapters = json.read(Store.string(novel, "plan")).path("chapters");
        int last = chapters.get(chapters.size() - 1).path("number").asInt();
        stateAfterChapter(novelId, chapter, last, nextVersion);
        store.event(novelId, Store.string(task, "id"), "CHAPTER_ACCEPTED", "第" + chapter + "章，记忆版本=" + nextVersion);
        store.event(novelId, Store.string(task, "id"), "MEMORY_VALIDATED", "记忆文件校验通过，正式记忆已保存");
    }

    private void stateAfterChapter(String novelId, int chapter, int last, long nextVersion) {
        boolean stageComplete = chapter >= last;
        store.update("novels.advance", nextVersion, chapter + 1,
                stageComplete ? "AWAITING_STAGE_REVIEW" : "WRITING", novelId);
        if (!stageComplete) schedule(store.novel(novelId), "WRITE", chapter + 1, 0, Map.of());
    }

    public void ensureReviewTasks(Map<String, Object> novel, int chapter, int revision) {
        reviews.start(novel, chapter, revision);
    }

    public static JsonNode memoryDelta(JsonNode payload) {
        return payload.has("delta") ? payload.get("delta") : payload;
    }

    public String repairMemory(Map<String, Object> novel, Map<String, Object> source, JsonNode delta,
            JsonNode review, String instructions, int attempt) {
        String novelId = Store.string(novel, "id");
        state(novelId, "EXTRACTING");
        String id = store.enqueue(novelId, "MEMORY_REPAIR", Store.integer(source, "chapter_no"),
                Store.integer(source, "revision"), Store.number(source, "memory_version"),
                novelId + ":memory-repair:" + source.get("id"),
                Map.of("delta", delta, "review", review, "instructions", instructions, "repairAttempt", attempt));
        store.event(novelId, id, "MEMORY_REPAIR_STARTED", "开始修正记忆，第" + attempt + "次；正文和大纲保持当前版本");
        return id;
    }

    public String scheduleMemoryCheck(Map<String, Object> novel, Map<String, Object> source, JsonNode delta, int attempt) {
        String novelId = Store.string(novel, "id");
        state(novelId, "CHECKING_MEMORY");
        if(delta.has("files")) {
            var draft=store.one("chapters.find",novelId,source.get("chapter_no"));
            delta=memory.validateDelta(delta,Store.string(draft,"body"));
            memory.files().validateBase(novelId,Store.number(source,"memory_version"),delta);
        }
        return store.enqueue(novelId, "CHECK_MEMORY", Store.integer(source, "chapter_no"),
                Store.integer(source, "revision"), Store.number(source, "memory_version"),
                novelId + ":memory-check:" + source.get("id"),
                Map.of("delta", delta));
    }

    private void state(String id, String state) {
        store.update("novels.state", state, id);
    }
}
