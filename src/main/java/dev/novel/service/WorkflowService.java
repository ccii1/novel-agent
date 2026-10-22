package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class WorkflowService {
    private final Store store;
    private final Json json;
    private final MemoryService memory;
    private final ChapterQualityService quality;
    private final ResearchService research;
    private final PlanChangeService planChanges;
    private final dev.novel.agent.orchestration.ReviewOrchestrator reviews;
    private final TaskScheduler scheduler;
    public static final int MEMORY_REPAIR_LIMIT=3;
    public WorkflowService(Store store,Json json,MemoryService memory,ChapterQualityService quality,ResearchService research,PlanChangeService planChanges,
            dev.novel.agent.orchestration.ReviewOrchestrator reviews,TaskScheduler scheduler) { this.store=store;this.json=json;this.memory=memory;this.quality=quality;this.research=research;this.planChanges=planChanges;this.reviews=reviews;this.scheduler=scheduler; }
    public String schedule(Map<String,Object> novel,String kind,int chapter,int revision,Object payload) {
        return scheduler.schedule(novel,kind,chapter,revision,payload);
    }
    public void finished(Map<String,Object> task,JsonNode result) {
        store.transaction(() -> {
            String taskId=(String)task.get("id"), novelId=(String)task.get("novel_id"), kind=(String)task.get("kind");
            Map<String,Object> novel=novelId==null?null:store.lockNovel(novelId);
            var persisted=store.one("tasks.lock",taskId);
            if(!"RUNNING".equals(persisted.get("status"))) return null;
            if(kind.equals("PLAN_CHANGE") || kind.equals("COORDINATE")) {
                if(kind.equals("PLAN_CHANGE")) planChanges.finished(task,result);
                store.update("tasks.done",json.write(result),taskId);
                store.event(novelId,taskId,"DONE",kind);
                return null;
            }
            if(novel!=null && (Set.of("PAUSED","ERROR","BUDGET_PAUSED").contains(novel.get("status")))) {
                store.update("tasks.ready",json.write(result),taskId);
                return null;
            }
            if(novel!=null && Store.number(novel,"memory_version")!=Store.number(task,"memory_version")) {
                store.update("tasks.stale",taskId);
                return null;
            }
            store.update("tasks.done",json.write(result),taskId);
            store.event(novelId,taskId,"DONE",kind);
            if(kind.equals("RESEARCH") || kind.equals("RESEARCH_CHAT")) {
                research.finished(task,result);
                return null;
            }
            int chapter=Store.integer(task,"chapter_no"), revision=Store.integer(task,"revision");
            switch(kind) {
                case "PLAN" -> store.update("novels.awaitPlan",json.write(result),novelId);
                case "WRITE", "REWRITE" -> {
                    var old=store.list("chapters.find",novelId,chapter);
                    if(!old.isEmpty()) {
                        var previous=old.get(0);
                        store.update("chapters.history",UUID.randomUUID().toString(),novelId,chapter,previous.get("revision"),previous.get("title"),previous.get("body"));
                    }
                    store.update("chapters.draft",
                            novelId,chapter,novel.get("stage_no"),revision,json.text(result,"title"),json.text(result,"body"),novel.get("memory_version"));
                    store.update("novels.state","CHECKING",novelId);
                    quality.record(novelId,chapter,revision,json.text(result,"body"));
                    ensureReviewTasks(novel,chapter,revision);
                }
                case "CHECK_CHARACTER", "CHECK_PLOT", "CHECK_PACING", "CHECK_STYLE", "CHECK_CHAPTER" -> {
                    store.event(novelId,taskId,result.path("approved").asBoolean()?"REVIEW_APPROVED":"REVIEW_REJECTED",
                            result.path("approved").asBoolean()?"正文审核通过":"正文审核未通过："+result.path("issues").size()+"项问题");
                    if(kind.equals("CHECK_PACING") || kind.equals("CHECK_CHAPTER")) store.update("quality.pacing",
                            json.write(result.path("narrative")),novelId,chapter,revision);
                    var round=reviews.collect(novel,chapter,revision);
                    if(round.ready()) {
                        if(round.approved()) { state(novelId,"EXTRACTING");schedule(novel,"MEMORY",chapter,revision,Map.of()); }
                        else { state(novelId,"REFLECTING");schedule(novel,"REFLECT",chapter,revision,Map.of()); }
                    }
                }
                case "REFLECT" -> {
                    boolean major=result.path("majorChange").asBoolean() || reviews.collect(novel,chapter,revision).majorChange();
                    store.update("novels.reflection",json.write(result),novelId);
                    if(major) state(novelId,"AWAITING_CHANGE");
                    else if(revision>=3) state(novelId,"AWAITING_HELP");
                    else { state(novelId,"WRITING");schedule(novel,"REWRITE",chapter,revision+1,Map.of()); }
                }
                case "MEMORY", "MEMORY_REPAIR" -> {
                    var payload=json.read(Store.string(task,"payload"));
                    scheduleMemoryCheck(novel,task,result,payload.path("repairAttempt").asInt(0));
                }
                case "CHECK_MEMORY" -> {
                    var checkPayload=json.read(Store.string(task,"payload"));
                    if(!result.path("approved").asBoolean() || result.path("majorChange").asBoolean() && !checkPayload.path("humanApproved").asBoolean()) {
                        var payload=json.read(Store.string(task,"payload"));
                        int attempt=payload.path("repairAttempt").asInt(0);
                        store.event(novelId,taskId,"REVIEW_REJECTED","记忆审核未通过："+result.path("issues").size()+"项问题");
                        if(!result.path("majorChange").asBoolean() && attempt<MEMORY_REPAIR_LIMIT) {
                            repairMemory(novel,task,memoryDelta(payload),result,"按审核意见修正记忆，保持正文和大纲。",attempt+1);
                        } else {
                            store.update("novels.help",json.write(Map.of("type","MEMORY_REVIEW","sourceTaskId",taskId,
                                    "repairAttempt",attempt,"majorChange",result.path("majorChange").asBoolean(),
                                    "instructions",result.path("majorChange").asBoolean()?"记忆变更需要人工确认。":"记忆自动修正已达到三次，请查看问题并提供记忆修改要求。",
                                    "issues",result.get("issues"))),novelId);
                        }
                    } else {
                        var draft=store.one("chapters.find",novelId,chapter);
                        if(Store.integer(draft,"revision")!=revision || Store.integer(novel,"chapter_no")!=chapter)
                            throw new IllegalStateException("记忆审核对应的正文版本已经变化");
                        var delta=memory.validateDelta(memoryDelta(json.read(Store.string(task,"payload"))),Store.string(draft,"body"));
                        long nextVersion=Store.number(novel,"memory_version")+1;
                        memory.commit(novelId,chapter,nextVersion,delta);
                        store.update("chapters.accept",json.text(delta,"summary"),nextVersion,novelId,chapter);
                        var plan=json.read((String)novel.get("plan"));
                        int last=plan.get("chapters").get(plan.get("chapters").size()-1).path("number").asInt();
                        store.update("novels.advance",nextVersion,chapter+1,chapter>=last?"AWAITING_STAGE_REVIEW":"WRITING",novelId);
                        if(chapter<last) schedule(store.novel(novelId),"WRITE",chapter+1,0,Map.of());
                        store.event(novelId,taskId,"CHAPTER_ACCEPTED","第"+chapter+"章，记忆版本="+nextVersion);
                        store.event(novelId,taskId,"REVIEW_APPROVED","记忆审核通过，正式记忆已保存");
                    }
                }
                default -> throw new IllegalArgumentException("未知流程步骤："+kind);
            }
            return null;
        });
    }
    public void ensureReviewTasks(Map<String,Object> novel,int chapter,int revision) {
        reviews.start(novel,chapter,revision);
    }
    public static JsonNode memoryDelta(JsonNode payload) { return payload.has("delta")?payload.get("delta"):payload; }
    public String repairMemory(Map<String,Object> novel,Map<String,Object> source,JsonNode delta,JsonNode review,String instructions,int attempt) {
        state(Store.string(novel,"id"),"EXTRACTING");
        String id=store.enqueue(Store.string(novel,"id"),"MEMORY_REPAIR",Store.integer(source,"chapter_no"),Store.integer(source,"revision"),
                Store.number(source,"memory_version"),novel.get("id")+":memory-repair:"+source.get("id"),Map.of("delta",delta,"review",review,"instructions",instructions,"repairAttempt",attempt));
        store.event(Store.string(novel,"id"),id,"MEMORY_REPAIR_STARTED","开始修正记忆，第"+attempt+"次；正文和大纲保持当前版本");
        return id;
    }
    public String scheduleMemoryCheck(Map<String,Object> novel,Map<String,Object> source,JsonNode delta,int attempt) {
        state(Store.string(novel,"id"),"CHECKING_MEMORY");
        return store.enqueue(Store.string(novel,"id"),"CHECK_MEMORY",Store.integer(source,"chapter_no"),Store.integer(source,"revision"),
                Store.number(source,"memory_version"),novel.get("id")+":memory-check:"+source.get("id"),Map.of("delta",delta,"repairAttempt",attempt,
                        "humanApproved",json.read(Store.string(source,"payload")).path("humanApproved").asBoolean(false)));
    }
    private void state(String id,String state) { store.update("novels.state",state,id); }
}
