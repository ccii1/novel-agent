package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class WorkflowService {
    private final Store store;
    private final Json json;
    private final MemoryService memory;
    private final ChapterQualityService quality;
    public WorkflowService(Store store,Json json,MemoryService memory,ChapterQualityService quality) { this.store=store;this.json=json;this.memory=memory;this.quality=quality; }
    public String schedule(Map<String,Object> novel,String kind,int chapter,int revision,Object payload) {
        String id=(String)novel.get("id");long version=Store.number(novel,"memory_version");
        String key=id+":"+novel.get("stage_no")+":"+chapter+":"+revision+":"+version+":"+kind;
        return store.enqueue(id,kind,chapter,revision,version,key,payload);
    }
    public void finished(Map<String,Object> task,JsonNode result) {
        store.transaction(() -> {
            String taskId=(String)task.get("id"), novelId=(String)task.get("novel_id"), kind=(String)task.get("kind");
            Map<String,Object> novel=novelId==null?null:store.lockNovel(novelId);
            var persisted=store.one("SELECT * FROM tasks WHERE id=? FOR UPDATE",taskId);
            if(!"RUNNING".equals(persisted.get("status"))) return null;
            if(novel!=null && (Set.of("PAUSED","ERROR","BUDGET_PAUSED").contains(novel.get("status")))) {
                store.db.update("UPDATE tasks SET result=?,status='READY',updated_at=CURRENT_TIMESTAMP WHERE id=?",json.write(result),taskId);
                return null;
            }
            if(novel!=null && Store.number(novel,"memory_version")!=Store.number(task,"memory_version")) {
                store.db.update("UPDATE tasks SET status='CANCELLED',error='记忆版本已变化' WHERE id=?",taskId);
                return null;
            }
            store.db.update("UPDATE tasks SET result=?,status='DONE',updated_at=CURRENT_TIMESTAMP WHERE id=?",json.write(result),taskId);
            store.event(novelId,taskId,"DONE",kind);
            if(kind.equals("RESEARCH")) {
                store.db.update("INSERT INTO research(id,task_id,sources,ideas) VALUES(?,?,?,?)",UUID.randomUUID().toString(),taskId,json.write(result.get("sources")),json.write(result.get("ideas")));
                return null;
            }
            int chapter=Store.integer(task,"chapter_no"), revision=Store.integer(task,"revision");
            switch(kind) {
                case "PLAN" -> store.db.update("UPDATE novels SET plan=?,status='AWAITING_PLAN',error=NULL WHERE id=?",json.write(result),novelId);
                case "WRITE", "REWRITE" -> {
                    var old=store.list("SELECT * FROM chapters WHERE novel_id=? AND number=?",novelId,chapter);
                    if(!old.isEmpty()) {
                        var previous=old.get(0);
                        store.db.update("INSERT INTO chapter_history(id,novel_id,number,revision,title,body) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),novelId,chapter,previous.get("revision"),previous.get("title"),previous.get("body"));
                    }
                    store.db.update("MERGE INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version,published) KEY(novel_id,number) VALUES(?,?,?,?,?,?,'DRAFT',?,FALSE)",
                            novelId,chapter,novel.get("stage_no"),revision,json.text(result,"title"),json.text(result,"body"),novel.get("memory_version"));
                    store.db.update("UPDATE novels SET status='CHECKING' WHERE id=?",novelId);
                    quality.record(novelId,chapter,revision,json.text(result,"body"));
                    ensureReviewTasks(novel,chapter,revision);
                }
                case "CHECK_CHARACTER", "CHECK_PLOT", "CHECK_PACING", "CHECK_STYLE" -> {
                    if(kind.equals("CHECK_PACING")) store.db.update("UPDATE chapter_metrics SET pacing_data=? WHERE novel_id=? AND chapter_no=? AND revision=?",
                            json.write(result.path("narrative")),novelId,chapter,revision);
                    var checks=reviews(novelId,chapter,revision,Store.number(task,"memory_version"));
                    if(checks.size()==AgentRoles.CHAPTER_CHECKS.size()) {
                        boolean approved=checks.stream().allMatch(row->json.read((String)row.get("result")).path("approved").asBoolean());
                        if(approved) { state(novelId,"EXTRACTING");schedule(novel,"MEMORY",chapter,revision,Map.of()); }
                        else { state(novelId,"REFLECTING");schedule(novel,"REFLECT",chapter,revision,Map.of()); }
                    }
                }
                case "REFLECT" -> {
                    boolean major=result.path("majorChange").asBoolean() || reviews(novelId,chapter,revision,Store.number(task,"memory_version")).stream()
                            .anyMatch(row->json.read((String)row.get("result")).path("majorChange").asBoolean());
                    store.db.update("UPDATE novels SET reflection=? WHERE id=?",json.write(result),novelId);
                    if(major) state(novelId,"AWAITING_CHANGE");
                    else if(revision>=3) state(novelId,"AWAITING_HELP");
                    else { state(novelId,"WRITING");schedule(novel,"REWRITE",chapter,revision+1,Map.of()); }
                }
                case "MEMORY" -> {
                    state(novelId,"CHECKING_MEMORY");schedule(novel,"CHECK_MEMORY",chapter,revision,result);
                }
                case "CHECK_MEMORY" -> {
                    if(!result.path("approved").asBoolean()) {
                        store.db.update("UPDATE novels SET status='AWAITING_HELP',reflection=? WHERE id=?",json.write(Map.of("instructions","记忆检查未通过，请核对章节中的事实与关系。","issues",result.get("issues"))),novelId);
                    } else {
                        var delta=json.read(Store.string(task,"payload"));
                        long nextVersion=Store.number(novel,"memory_version")+1;
                        memory.commit(novelId,chapter,nextVersion,delta);
                        store.db.update("UPDATE chapters SET status='ACCEPTED',summary=?,memory_version=? WHERE novel_id=? AND number=?",json.text(delta,"summary"),nextVersion,novelId,chapter);
                        var plan=json.read((String)novel.get("plan"));
                        int last=plan.get("chapters").get(plan.get("chapters").size()-1).path("number").asInt();
                        store.db.update("UPDATE novels SET memory_version=?,chapter_no=?,reflection=NULL,status=? WHERE id=?",nextVersion,chapter+1,chapter>=last?"AWAITING_STAGE_REVIEW":"WRITING",novelId);
                        if(chapter<last) schedule(store.novel(novelId),"WRITE",chapter+1,0,Map.of());
                        store.event(novelId,taskId,"CHAPTER_ACCEPTED","第"+chapter+"章，记忆版本="+nextVersion);
                    }
                }
                default -> throw new IllegalArgumentException("未知流程步骤："+kind);
            }
            return null;
        });
    }
    public void ensureReviewTasks(Map<String,Object> novel,int chapter,int revision) {
        for(String check:AgentRoles.CHAPTER_CHECKS) schedule(novel,check,chapter,revision,Map.of());
    }
    private void state(String id,String state) { store.db.update("UPDATE novels SET status=? WHERE id=?",state,id); }
    private List<Map<String,Object>> reviews(String id,int chapter,int revision,long version) {
        return store.list("SELECT kind,result FROM tasks WHERE novel_id=? AND chapter_no=? AND revision=? AND memory_version=? AND kind IN ('CHECK_CHARACTER','CHECK_PLOT','CHECK_PACING','CHECK_STYLE') AND status='DONE'",id,chapter,revision,version);
    }
}
