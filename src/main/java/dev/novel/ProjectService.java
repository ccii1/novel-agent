package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ProjectService {
    private final Store store;
    private final WorkflowService workflow;
    private final AgentService agents;
    private final MemoryService memory;
    private final Json json;
    public ProjectService(Store store,WorkflowService workflow,AgentService agents,MemoryService memory,Json json) {
        this.store=store;this.workflow=workflow;this.agents=agents;this.memory=memory;this.json=json;
    }
    public String create(String title,String premise,String bible,int size,BigDecimal novelBudget,BigDecimal chapterBudget) {
        if(size<1 || size>20) throw new IllegalArgumentException("每阶段章节数量必须为1至20");
        if(novelBudget.signum()<=0 || chapterBudget.signum()<=0) throw new IllegalArgumentException("预算必须大于零");
        return store.transaction(() -> {
            String id=UUID.randomUUID().toString();
            store.db.update("INSERT INTO novels(id,title,premise,bible,status,stage_size,novel_budget,chapter_budget) VALUES(?,?,?,?,'PLANNING',?,?,?)",id,title,premise,bible,size,novelBudget,chapterBudget);
            workflow.schedule(store.novel(id),"PLAN",1,0,Map.of());
            return id;
        });
    }
    public Map<String,Object> detail(String id) {
        var novel=store.novel(id);
        return Map.of("novel",novel,
                "chapters",store.list("SELECT number,stage_no,revision,title,status,memory_version,published,summary FROM chapters WHERE novel_id=? ORDER BY number",id),
                "tasks",store.list("SELECT * FROM tasks WHERE novel_id=? ORDER BY created_at DESC LIMIT 100",id),
                "events",store.list("SELECT * FROM events WHERE novel_id=? ORDER BY id DESC LIMIT 100",id),
                "edits",store.list("SELECT * FROM edit_requests WHERE novel_id=? ORDER BY created_at DESC LIMIT 30",id));
    }
    public void approvePlan(String id,JsonNode plan) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);require(novel,"AWAITING_PLAN");
            JsonNode approved=plan==null || plan.isNull()?json.read((String)novel.get("plan")):plan;
            agents.validatePlan(approved,Store.integer(novel,"chapter_no"),Store.integer(novel,"stage_size"));
            store.db.update("UPDATE novels SET plan=?,status='WRITING' WHERE id=?",json.write(approved),id);
            workflow.schedule(store.novel(id),"WRITE",Store.integer(novel,"chapter_no"),0,Map.of());
            store.event(id,null,"PLAN_APPROVED","阶段大纲已批准");
            return null;
        });
    }
    public void continueStage(String id) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);require(novel,"AWAITING_STAGE_REVIEW");
            store.db.update("UPDATE novels SET stage_no=stage_no+1,status='PLANNING',plan=NULL WHERE id=?",id);
            workflow.schedule(store.novel(id),"PLAN",Store.integer(novel,"chapter_no"),0,Map.of());
            store.event(id,null,"STAGE_APPROVED","已开始下一阶段规划"); return null;
        });
    }
    public void pause(String id) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);
            if(!Set.of("PLANNING","WRITING","CHECKING","REFLECTING","EXTRACTING","CHECKING_MEMORY").contains(novel.get("status")))
                throw new IllegalStateException("当前阶段无需暂停");
            store.db.update("UPDATE novels SET resume_status=status,status='PAUSED' WHERE id=?",id);
            store.event(id,null,"PAUSED","暂停后已发出的调用仍会结算，结果保留等待恢复");return null;
        });
    }
    public void resume(String id) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);require(novel,"PAUSED");
            store.db.update("UPDATE novels SET status=resume_status,resume_status=NULL WHERE id=?",id);
            store.event(id,null,"RESUMED","已恢复执行");return null;
        });
    }
    public void revise(String id,String instructions,JsonNode plan) {
        if(instructions==null || instructions.isBlank() || instructions.length()>12000) throw new IllegalArgumentException("请提供有效修改要求，最多12000字符");
        store.transaction(() -> {
            var novel=store.lockNovel(id);require(novel,"AWAITING_CHANGE","AWAITING_HELP");
            if("AWAITING_CHANGE".equals(novel.get("status")) && (plan==null || plan.isNull())) throw new IllegalArgumentException("重大变更需要提供完整批准的大纲");
            if(plan!=null && !plan.isNull()) {
                var old=json.read((String)novel.get("plan"));
                agents.validatePlan(plan,old.get("chapters").get(0).path("number").asInt(),Store.integer(novel,"stage_size"));
                store.db.update("UPDATE novels SET plan=? WHERE id=?",json.write(plan),id);
            }
            int chapter=Store.integer(novel,"chapter_no");
            int revision=Store.integer(store.one("SELECT revision FROM chapters WHERE novel_id=? AND number=?",id,chapter),"revision")+1;
            store.db.update("UPDATE novels SET status='WRITING',reflection=? WHERE id=?",json.write(Map.of("instructions",instructions,"humanApproved",true)),id);
            workflow.schedule(store.novel(id),"REWRITE",chapter,revision,Map.of());
            store.event(id,null,"REVISION_APPROVED",instructions);return null;
        });
    }
    public String retry(String taskId) {
        return store.transaction(() -> {
            var original=store.one("SELECT * FROM tasks WHERE id=?",taskId);
            String novelId=(String)original.get("novel_id");
            Map<String,Object> novel=novelId==null?null:store.lockNovel(novelId);
            var task=store.one("SELECT * FROM tasks WHERE id=? FOR UPDATE",taskId);
            if(!"ERROR".equals(task.get("status"))) throw new IllegalStateException("仅可重新执行失败任务");
            if(!store.list("SELECT id FROM model_calls WHERE task_id=? AND status IN ('UNCERTAIN','IN_FLIGHT')",taskId).isEmpty())
                throw new IllegalStateException("请先核对供应商费用");
            if(novel!=null && Store.number(novel,"memory_version")!=Store.number(task,"memory_version")) throw new IllegalStateException("任务记忆版本已失效");
            store.db.update("UPDATE tasks SET status='CANCELLED' WHERE id=?",taskId);
            String next=store.enqueue(novelId,(String)task.get("kind"),Store.integer(task,"chapter_no"),Store.integer(task,"revision"),Store.number(task,"memory_version"),
                    task.get("task_key")+":retry:"+UUID.randomUUID(),json.read((String)task.get("payload")));
            if(novel!=null) {
                require(novel,"ERROR","BUDGET_PAUSED","PLANNING","WRITING","CHECKING","REFLECTING","EXTRACTING","CHECKING_MEMORY");
                if(Set.of("ERROR","BUDGET_PAUSED").contains(novel.get("status")))
                    store.db.update("UPDATE novels SET status=resume_status,resume_status=NULL,error=NULL WHERE id=?",novelId);
            }
            store.event(novelId,next,"RETRY_APPROVED","人工确认重新执行，可能产生新的调用费用");return next;
        });
    }
    public Map<String,Object> requestEdit(String id,int chapter,String instructions) {
        if(instructions==null || instructions.isBlank() || instructions.length()>12000) throw new IllegalArgumentException("请提供有效修改要求");
        return store.transaction(() -> {
            var novel=store.lockNovel(id);
            require(novel,"PAUSED","AWAITING_PLAN","AWAITING_STAGE_REVIEW","AWAITING_CHANGE","AWAITING_HELP","ERROR","BUDGET_PAUSED");
            if(!store.list("SELECT id FROM tasks WHERE novel_id=? AND status='RUNNING'",id).isEmpty()) throw new IllegalStateException("请等待已开始的任务结束");
            var original=store.one("SELECT * FROM chapters WHERE novel_id=? AND number=?",id,chapter);
            if(!"ACCEPTED".equals(original.get("status"))) throw new IllegalArgumentException("历史修改需要选择已通过检查的章节");
            var affected=store.list("SELECT number,title,published FROM chapters WHERE novel_id=? AND number>=? ORDER BY number",id,chapter);
            var facts=memory.snapshot(id,Store.number(novel,"memory_version")).stream().filter(row->Store.integer(row,"chapter_no")>=chapter).toList();
            var impact=Map.of("chapters",affected,"memories",facts,"reason","后续章节可能依赖修改章节，将逐章重新生成并检查；公开平台正文仍由人工处理");
            String edit=UUID.randomUUID().toString();
            store.db.update("INSERT INTO edit_requests(id,novel_id,chapter_no,base_version,instruction,impact,status) VALUES(?,?,?,?,?,?,'PENDING')",edit,id,chapter,novel.get("memory_version"),instructions,json.write(impact));
            return Map.of("id",edit,"impact",impact);
        });
    }
    public void approveEdit(String editId,boolean acknowledgePublished) {
        store.transaction(() -> {
            var edit=store.one("SELECT * FROM edit_requests WHERE id=?",editId);
            String id=(String)edit.get("novel_id");var novel=store.lockNovel(id);
            if(!"PENDING".equals(edit.get("status"))) throw new IllegalStateException("修改请求已经处理");
            if(Store.number(edit,"base_version")!=Store.number(novel,"memory_version")) throw new IllegalStateException("记忆已经变化，请重新分析影响范围");
            require(novel,"PAUSED","AWAITING_PLAN","AWAITING_STAGE_REVIEW","AWAITING_CHANGE","AWAITING_HELP","ERROR","BUDGET_PAUSED");
            if(!store.list("SELECT id FROM tasks WHERE novel_id=? AND status='RUNNING'",id).isEmpty()) throw new IllegalStateException("请等待执行中的任务结束");
            if(!store.list("SELECT id FROM model_calls WHERE novel_id=? AND status IN ('UNCERTAIN','IN_FLIGHT')",id).isEmpty()) throw new IllegalStateException("请先核对结果不确定的调用费用");
            int chapter=Store.integer(edit,"chapter_no");
            if(!acknowledgePublished && !store.list("SELECT number FROM chapters WHERE novel_id=? AND number>=? AND published=TRUE",id,chapter).isEmpty())
                throw new IllegalStateException("影响范围包含已发布章节，需要明确确认");
            long version=Store.number(novel,"memory_version"), next=version+1;
            var baseline=store.list("SELECT m.* FROM memories m WHERE novel_id=? AND chapter_no<? AND version<=? AND version=(SELECT MAX(n.version) FROM memories n WHERE n.novel_id=m.novel_id AND n.memory_key=m.memory_key AND n.chapter_no<? AND n.version<=?)",id,chapter,version,chapter,version);
            Map<String,Map<String,Object>> before=new HashMap<>();baseline.forEach(row->before.put((String)row.get("memory_key"),row));
            for(var row:memory.snapshot(id,version)) {
                if(Store.integer(row,"chapter_no")<chapter) continue;
                var old=before.get(row.get("memory_key"));
                String content=old==null?json.write(Map.of("deleted",true,"name",row.get("memory_key"))):(String)old.get("content");
                store.db.update("INSERT INTO memories(id,novel_id,memory_key,kind,chapter_no,version,content) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,row.get("memory_key"),row.get("kind"),old==null?chapter-1:old.get("chapter_no"),next,content);
            }
            store.db.update("UPDATE tasks SET status='CANCELLED' WHERE novel_id=? AND status IN ('WAITING','READY','ERROR')",id);
            store.db.update("UPDATE chapters SET status='INVALIDATED' WHERE novel_id=? AND number>=?",id,chapter);
            store.db.update("UPDATE novels SET status='PLANNING',stage_no=stage_no+1,chapter_no=?,memory_version=?,plan=NULL,reflection=NULL,resume_status=NULL,error=NULL WHERE id=?",chapter,next,id);
            workflow.schedule(store.novel(id),"PLAN",chapter,0,Map.of("historicalEdit",edit.get("instruction"),"impact",json.read((String)edit.get("impact"))));
            store.db.update("UPDATE edit_requests SET status='APPROVED' WHERE id=?",editId);
            store.event(id,null,"EDIT_APPROVED","从第"+chapter+"章重新规划和生成");return null;
        });
    }
    private void require(Map<String,Object> novel,String... states) {
        if(!Arrays.asList(states).contains(novel.get("status"))) throw new IllegalStateException("当前状态不允许此操作："+novel.get("status"));
    }
}
