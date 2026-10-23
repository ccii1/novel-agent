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
    public static final int MEMORY_REPAIR_LIMIT = 3;
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

    public WorkflowService(Store store, Json json, MemoryService memory, ChapterQualityService quality,
            ResearchService research, PlanChangeService planChanges, ReviewOrchestrator reviews, TaskScheduler scheduler) {
        this.store = store;
        this.json = json;
        this.memory = memory;
        this.quality = quality;
        this.research = research;
        this.planChanges = planChanges;
        this.reviews = reviews;
        this.scheduler = scheduler;
    }

    public String schedule(Map<String, Object> novel, String kind, int chapter, int revision, Object payload) {
        return scheduler.schedule(novel, kind, chapter, revision, payload);
    }

    public void finished(Map<String, Object> task, JsonNode result) {
        MemoryFileService.Prepared prepared=null;
        var payload=json.read(Store.string(task,"payload"));
        if("CHECK_MEMORY".equals(task.get("kind")) && "COVERAGE".equals(payload.path("reviewPhase").asText())
                && result.path("approved").asBoolean() && (!result.path("majorChange").asBoolean() || payload.path("humanApproved").asBoolean())) {
            var novel=store.novel(Store.string(task,"novel_id"));
            if(!SUSPENDED.contains(novel.get("status")) && Store.number(novel,"memory_version")==Store.number(task,"memory_version")
                    && "RUNNING".equals(store.one("tasks.find",task.get("id")).get("status"))) {
                var draft=store.one("chapters.find",task.get("novel_id"),task.get("chapter_no"));
                var delta=memory.validateDelta(memoryDelta(payload),Store.string(draft,"body"));
                if(Store.integer(draft,"revision")!=Store.integer(task,"revision")) throw new IllegalStateException("记忆审核对应的正文版本已经变化");
                prepared=memory.files().prepare(Store.string(task,"novel_id"),Store.integer(task,"chapter_no"),Store.integer(task,"revision"),
                        Store.number(task,"memory_version")+1,delta);
            }
        }
        var publication=prepared;
        store.transaction(() -> {
            finishWithinTransaction(task, result,publication);
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
            case "MEMORY", "MEMORY_REPAIR" -> scheduleMemoryCheck(novel, task, result,
                    json.read(Store.string(task, "payload")).path("repairAttempt").asInt(0));
            case "CHECK_MEMORY" -> finishMemoryReview(novel, task, result,prepared);
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
        state(novelId, round.approved() ? "EXTRACTING" : "REFLECTING");
        schedule(novel, round.approved() ? "MEMORY" : "REFLECT", chapter, revision, Map.of());
    }

    private void finishReflection(Map<String, Object> novel, Map<String, Object> task, JsonNode result) {
        String novelId = Store.string(novel, "id");
        int chapter = Store.integer(task, "chapter_no");
        int revision = Store.integer(task, "revision");
        boolean major = result.path("majorChange").asBoolean() || reviews.collect(novel, chapter, revision).majorChange();
        store.update("novels.reflection", json.write(result), novelId);
        if (major) {
            state(novelId, "AWAITING_CHANGE");
        } else if (revision >= REVISION_LIMIT) {
            state(novelId, "AWAITING_HELP");
        } else {
            state(novelId, "WRITING");
            schedule(novel, "REWRITE", chapter, revision + 1, Map.of());
        }
    }

    private void finishMemoryReview(Map<String, Object> novel, Map<String, Object> task, JsonNode result,MemoryFileService.Prepared prepared) {
        var payload = json.read(Store.string(task, "payload"));
        boolean major = result.path("majorChange").asBoolean();
        boolean authorized = !major || payload.path("humanApproved").asBoolean();
        if (result.path("approved").asBoolean() && authorized) {
            if(payload.has("reviewPhase")) {
                String fingerprint=SkillService.digest(json.write(memoryDelta(payload)));
                if(!fingerprint.equals(payload.path("changeSetHash").asText()))
                    throw new IllegalStateException("记忆候选内容与审核指纹不一致");
                if("FILES".equals(payload.path("reviewPhase").asText())) {
                    int next=payload.path("reviewIndex").asInt()+1;
                    scheduleFileReview(novel,task,memoryDelta(payload),payload.path("repairAttempt").asInt(),
                            next<payload.path("reviewGroups").size()?"FILES":"COVERAGE",next,
                            payload.path("humanApproved").asBoolean());
                    return;
                }
                if(!"COVERAGE".equals(payload.path("reviewPhase").asText())) throw new IllegalStateException("未知记忆审核阶段");
            }
            commitApprovedChapter(novel, task, memoryDelta(payload),prepared);
            return;
        }

        int attempt = payload.path("repairAttempt").asInt(0);
        store.event(Store.string(novel, "id"), Store.string(task, "id"), "REVIEW_REJECTED",
                "记忆审核未通过：" + result.path("issues").size() + "项问题");
        if (!major && attempt < MEMORY_REPAIR_LIMIT) {
            repairMemory(novel, task, memoryDelta(payload), result, "按审核意见修正记忆，保持正文和大纲。", attempt + 1);
            return;
        }
        String instructions = major ? "记忆变更需要人工确认。" : "记忆自动修正已达到三次，请查看问题并提供记忆修改要求。";
        store.update("novels.help", json.write(Map.of(
                "type", "MEMORY_REVIEW", "sourceTaskId", task.get("id"), "repairAttempt", attempt,
                "majorChange", major, "instructions", instructions, "issues", result.get("issues"))), novel.get("id"));
    }

    private void commitApprovedChapter(Map<String, Object> novel, Map<String, Object> task, JsonNode pending,MemoryFileService.Prepared prepared) {
        String novelId = Store.string(novel, "id");
        int chapter = Store.integer(task, "chapter_no");
        var draft = store.one("chapters.find", novelId, chapter);
        if (Store.integer(draft, "revision") != Store.integer(task, "revision")
                || Store.integer(novel, "chapter_no") != chapter) {
            throw new IllegalStateException("记忆审核对应的正文版本已经变化");
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
        store.event(novelId, Store.string(task, "id"), "REVIEW_APPROVED", "记忆审核通过，正式记忆已保存");
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
        boolean humanApproved = json.read(Store.string(source, "payload")).path("humanApproved").asBoolean(false);
        if(delta.has("files")) {
            var draft=store.one("chapters.find",novelId,source.get("chapter_no"));
            delta=memory.validateDelta(delta,Store.string(draft,"body"));
            memory.files().stage(source,delta);
            return scheduleFileReview(novel,source,delta,attempt,"FILES",0,humanApproved);
        }
        return store.enqueue(novelId, "CHECK_MEMORY", Store.integer(source, "chapter_no"),
                Store.integer(source, "revision"), Store.number(source, "memory_version"),
                novelId + ":memory-check:" + source.get("id"),
                Map.of("delta", delta, "repairAttempt", attempt, "humanApproved", humanApproved));
    }

    private String scheduleFileReview(Map<String,Object> novel,Map<String,Object> source,JsonNode delta,int attempt,
            String phase,int index,boolean humanApproved) {
        String novelId=Store.string(novel,"id");
        var previous=json.read(Store.string(source,"payload"));
        Object groups=previous.has("reviewGroups") && !("FILES".equals(phase) && index==0)?previous.path("reviewGroups"):memory.files().reviewGroups(novelId,Store.number(source,"memory_version"),delta,
                Store.string(store.one("chapters.find",novelId,source.get("chapter_no")),"body"));
        var covered=new java.util.HashSet<Integer>();
        for(var group:json.tree(groups)) {
            if(!group.isArray() || group.isEmpty()) throw new IllegalStateException("记忆审核分组不能为空");
            for(var item:group) if(!item.canConvertToInt() || item.asInt()<0 || item.asInt()>=delta.path("files").size() || !covered.add(item.asInt()))
                throw new IllegalStateException("记忆审核分组存在重复或无效文件");
        }
        if(covered.size()!=delta.path("files").size()) throw new IllegalStateException("记忆审核分组没有覆盖全部候选文件");
        return store.enqueue(novelId,"CHECK_MEMORY",Store.integer(source,"chapter_no"),Store.integer(source,"revision"),
                Store.number(source,"memory_version"),novelId+":file-review:"+source.get("id")+":"+phase+":"+index,
                Map.of("delta",delta,"repairAttempt",attempt,"humanApproved",humanApproved,"reviewPhase",phase,
                        "reviewIndex",index,"reviewGroups",groups,"changeSetHash",SkillService.digest(json.write(delta))));
    }

    private void state(String id, String state) {
        store.update("novels.state", state, id);
    }
}
