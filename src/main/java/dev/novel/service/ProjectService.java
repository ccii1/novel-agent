package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.agent.PlanValidator;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.repository.mapper.MemoryMapper;
import dev.novel.repository.model.MemoryEntry;
import dev.novel.support.Json;
import dev.novel.integration.model.ModelCatalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ProjectService {
    private final Store store;
    private final WorkflowService workflow;
    private final PlanValidator plans;
    private final MemoryService memory;
    private final Json json;
    private final ModelCatalog catalog;
    private final ModelCallMapper calls;
    private final MemoryMapper memories;
    public ProjectService(Store store,WorkflowService workflow,PlanValidator plans,MemoryService memory,Json json,ModelCatalog catalog,ModelCallMapper calls,MemoryMapper memories) {
        this.store=store;this.workflow=workflow;this.plans=plans;this.memory=memory;this.json=json;
        this.catalog=catalog;
        this.calls=calls;
        this.memories=memories;
    }
    public String create(String title,String premise,String bible,int size,BigDecimal novelBudget,BigDecimal chapterBudget) {
        return create(title,premise,bible,size,novelBudget,chapterBudget,null,"");
    }
    public String create(String title,String premise,String bible,int size,BigDecimal novelBudget,BigDecimal chapterBudget,String provider,String model) {
        if(size<1 || size>20) throw new IllegalArgumentException("每阶段章节数量必须为1至20");
        if(novelBudget.signum()<=0 || chapterBudget.signum()<=0) throw new IllegalArgumentException("预算必须大于零");
        var selection=catalog.resolve(provider,model,"PLAN");
        return store.transaction(() -> {
            String id=UUID.randomUUID().toString();
            store.update("novels.create",id,title,premise,bible,size,novelBudget,chapterBudget,selection.provider(),Objects.toString(model,""));
            workflow.schedule(store.novel(id),"PLAN",1,0,Map.of());
            return id;
        });
    }
    public void selectModel(String id,String provider,String model) {
        var selection=catalog.resolve(provider,model,"PLAN");
        if(!selection.ready()) throw new IllegalArgumentException("模型服务尚未配置完成");
        store.transaction(()-> {
            var novel=store.lockNovel(id);
            require(novel,"PAUSED","AWAITING_PLAN","AWAITING_STAGE_REVIEW","AWAITING_CHANGE","AWAITING_HELP","ERROR","BUDGET_PAUSED");
            if(!store.list("tasks.runningNovel",id).isEmpty())
                throw new IllegalStateException("请等待已经开始的任务结束后切换模型");
            store.update("novels.model",selection.provider(),Objects.toString(model,""),id);
            store.event(id,null,"MODEL_SELECTED",selection.provider()+" / "+selection.model()+" / "+selection.billingMode());
            return null;
        });
    }
    public Map<String,Object> detail(String id) {
        var novel=store.novel(id);
        return Map.of("novel",novel,
                "chapters",store.list("chapters.byNovel",id),
                "tasks",store.list("tasks.byNovel",id),
                "events",store.list("events.byNovel",id),
                "edits",store.list("edits.byNovel",id));
    }
    public void approvePlan(String id,JsonNode plan) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);require(novel,"AWAITING_PLAN");
            JsonNode approved=plan==null || plan.isNull()?json.read((String)novel.get("plan")):plan;
            plans.validate(approved,Store.integer(novel,"chapter_no"),Store.integer(novel,"stage_size"));
            store.update("novels.approvePlan",json.write(approved),id);
            workflow.schedule(store.novel(id),"WRITE",Store.integer(novel,"chapter_no"),0,Map.of());
            store.event(id,null,"PLAN_APPROVED","阶段大纲已批准");
            return null;
        });
    }
    public void continueStage(String id) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);require(novel,"AWAITING_STAGE_REVIEW");
            store.update("novels.nextStage",id);
            workflow.schedule(store.novel(id),"PLAN",Store.integer(novel,"chapter_no"),0,Map.of());
            store.event(id,null,"STAGE_APPROVED","已开始下一阶段规划"); return null;
        });
    }
    public void pause(String id) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);
            if(!Set.of("PLANNING","WRITING","CHECKING","REFLECTING","EXTRACTING","CHECKING_MEMORY").contains(novel.get("status")))
                throw new IllegalStateException("当前阶段无需暂停");
            store.update("novels.pause",id);
            store.event(id,null,"PAUSED","暂停后已发出的调用仍会结算，结果保留等待恢复");return null;
        });
    }
    public void resume(String id) {
        store.transaction(() -> {
            var novel=store.lockNovel(id);require(novel,"PAUSED");
            store.update("novels.resume",id);
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
                plans.validate(plan,old.get("chapters").get(0).path("number").asInt(),Store.integer(novel,"stage_size"));
                store.update("novels.plan",json.write(plan),id);
            }
            int chapter=Store.integer(novel,"chapter_no");
            int revision=Store.integer(store.one("chapters.find",id,chapter),"revision")+1;
            store.update("novels.rewrite",json.write(Map.of("instructions",instructions,"humanApproved",true)),id);
            workflow.schedule(store.novel(id),"REWRITE",chapter,revision,Map.of());
            store.event(id,null,"REVISION_APPROVED",instructions);return null;
        });
    }
    public String retry(String taskId) {
        return retry(taskId,null,null);
    }
    public String retry(String taskId,String provider,String model) {
        return store.transaction(() -> {
            var original=store.one("tasks.find",taskId);
            String novelId=(String)original.get("novel_id");
            Map<String,Object> novel=novelId==null?null:store.lockNovel(novelId);
            var task=store.one("tasks.lock",taskId);
            if(!"ERROR".equals(task.get("status"))) throw new IllegalStateException("仅可重新执行失败任务");
            if(calls.pendingTaskCount(taskId)>0)
                throw new IllegalStateException("请先核对供应商费用");
            if(novel!=null && Store.number(novel,"memory_version")!=Store.number(task,"memory_version")) throw new IllegalStateException("任务记忆版本已失效");
            boolean override=provider!=null && !provider.isBlank() || model!=null && !model.isBlank();
            ModelCatalog.Selection choice=null;
            if(override) {
                choice=catalog.resolve(provider==null || provider.isBlank()?catalog.forTask(task).provider():provider,
                        Objects.toString(model,""),Store.string(task,"kind"));
                if(!choice.ready()) throw new IllegalArgumentException("所选模型服务尚未配置完成");
            }
            store.update("tasks.cancel",taskId);
            String next=store.enqueue(novelId,(String)task.get("kind"),Store.integer(task,"chapter_no"),Store.integer(task,"revision"),Store.number(task,"memory_version"),
                    task.get("task_key")+":retry:"+UUID.randomUUID(),json.read((String)task.get("payload")));
            if(task.get("research_id")!=null) store.update("tasks.linkResearch",task.get("research_id"),next);
            if(choice!=null) store.update("tasks.modelChoice",choice.provider(),choice.model(),next);
            else if(task.get("provider")!=null) store.update("tasks.modelChoice",task.get("provider"),task.get("model_name"),next);
            if(novel!=null) {
                require(novel,"ERROR","BUDGET_PAUSED","PLANNING","WRITING","CHECKING","REFLECTING","EXTRACTING","CHECKING_MEMORY");
                if(Set.of("ERROR","BUDGET_PAUSED").contains(novel.get("status")))
                    store.update("novels.retry",novelId);
            }
            store.event(novelId,next,"RETRY_APPROVED","人工确认重新执行，可能产生新的调用费用");return next;
        });
    }
    public Map<String,Object> requestEdit(String id,int chapter,String instructions) {
        if(instructions==null || instructions.isBlank() || instructions.length()>12000) throw new IllegalArgumentException("请提供有效修改要求");
        return store.transaction(() -> {
            var novel=store.lockNovel(id);
            require(novel,"PAUSED","AWAITING_PLAN","AWAITING_STAGE_REVIEW","AWAITING_CHANGE","AWAITING_HELP","ERROR","BUDGET_PAUSED");
            if(!store.list("tasks.runningNovel",id).isEmpty()) throw new IllegalStateException("请等待已开始的任务结束");
            var original=store.one("chapters.find",id,chapter);
            if(!"ACCEPTED".equals(original.get("status"))) throw new IllegalArgumentException("历史修改需要选择已通过检查的章节");
            var affected=store.list("chapters.affected",id,chapter);
            var facts=memory.snapshot(id,Store.number(novel,"memory_version")).stream().filter(row->Store.integer(row,"chapter_no")>=chapter).toList();
            var impact=Map.of("chapters",affected,"memories",facts,"reason","后续章节可能依赖修改章节，将逐章重新生成并检查；公开平台正文仍由人工处理");
            String edit=UUID.randomUUID().toString();
            store.update("edits.create",edit,id,chapter,novel.get("memory_version"),instructions,json.write(impact));
            return Map.of("id",edit,"impact",impact);
        });
    }
    public void approveEdit(String editId,boolean acknowledgePublished) {
        store.transaction(() -> {
            var edit=store.one("edits.find",editId);
            String id=(String)edit.get("novel_id");var novel=store.lockNovel(id);
            if(!"PENDING".equals(edit.get("status"))) throw new IllegalStateException("修改请求已经处理");
            if(Store.number(edit,"base_version")!=Store.number(novel,"memory_version")) throw new IllegalStateException("记忆已经变化，请重新分析影响范围");
            require(novel,"PAUSED","AWAITING_PLAN","AWAITING_STAGE_REVIEW","AWAITING_CHANGE","AWAITING_HELP","ERROR","BUDGET_PAUSED");
            if(!store.list("tasks.runningNovel",id).isEmpty()) throw new IllegalStateException("请等待执行中的任务结束");
            if(calls.pendingNovelCount(id)>0) throw new IllegalStateException("请先核对结果不确定的调用费用");
            int chapter=Store.integer(edit,"chapter_no");
            if(!acknowledgePublished && !store.list("chapters.published",id,chapter).isEmpty())
                throw new IllegalStateException("影响范围包含已发布章节，需要明确确认");
            long version=Store.number(novel,"memory_version"), next=version+1;
            var baseline=memories.baseline(id,chapter,version);
            Map<String,Map<String,Object>> before=new HashMap<>();baseline.forEach(row->before.put((String)row.get("memory_key"),row));
            for(var row:memory.snapshot(id,version)) {
                if(Store.integer(row,"chapter_no")<chapter) continue;
                var old=before.get(row.get("memory_key"));
                String content=old==null?json.write(Map.of("deleted",true,"name",row.get("memory_key"))):(String)old.get("content");
                memories.insert(new MemoryEntry(UUID.randomUUID().toString(),id,Store.string(row,"memory_key"),Store.string(row,"kind"),
                        old==null?chapter-1:Store.integer(old,"chapter_no"),next,content));
            }
            store.update("tasks.cancelNovel",id);
            store.update("chapters.invalidate",id,chapter);
            store.update("novels.edit",chapter,next,id);
            workflow.schedule(store.novel(id),"PLAN",chapter,0,Map.of("historicalEdit",edit.get("instruction"),"impact",json.read((String)edit.get("impact"))));
            store.update("edits.approve",editId);
            store.event(id,null,"EDIT_APPROVED","从第"+chapter+"章重新规划和生成");return null;
        });
    }
    private void require(Map<String,Object> novel,String... states) {
        if(!Arrays.asList(states).contains(novel.get("status"))) throw new IllegalStateException("当前状态不允许此操作："+novel.get("status"));
    }
}
