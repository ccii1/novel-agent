package dev.novel.engine;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.service.AgentService;
import dev.novel.service.WorkflowService;
import dev.novel.service.BudgetService;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TaskEngine {
    private final Store store;
    private final TaskDispatchMapper dispatchMapper;
    private final ModelCallMapper calls;
    private final AgentService agents;
    private final WorkflowService workflow;
    private final AsyncTaskExecutor workers;
    private final int limit;
    private final AtomicInteger active=new AtomicInteger();
    private final Logger log=LoggerFactory.getLogger(TaskEngine.class);
    private volatile boolean ready;
    @Value("${novel.engine-enabled:true}") private boolean enabled;
    public TaskEngine(Store store,TaskDispatchMapper dispatchMapper,ModelCallMapper calls,AgentService agents,WorkflowService workflow,
            @Qualifier("novelTaskExecutor") AsyncTaskExecutor workers,@Value("${novel.workers}") int limit) {
        if(limit<1 || limit>32) throw new IllegalArgumentException("workers 应为1至32");
        this.store=store;this.agents=agents;this.workflow=workflow;this.limit=limit;
        this.dispatchMapper=dispatchMapper;
        this.calls=calls;
        this.workers=workers;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        store.transaction(() -> {
            calls.recoverInterrupted();
            for(var task:store.list("tasks.running")) {
                if(calls.uncertainTaskCount(Store.string(task,"id"))==0) store.update("tasks.wait",task.get("id"));
                else fail(task,new IllegalStateException("服务中断期间调用结果不确定，请核对费用"));
            }
            for(var novel:store.list("novels.checking")) {
                var chapter=store.one("chapters.find",novel.get("id"),novel.get("chapter_no"));
                workflow.ensureReviewTasks(novel,Store.integer(novel,"chapter_no"),Store.integer(chapter,"revision"));
            }
            store.event(null,null,"RECOVERY","已恢复持久化任务，结果不确定的调用等待人工核对");
            return null;
        });
        ready=true;
    }
    @Scheduled(fixedDelay=500)
    public void dispatch() {
        if(!ready || !enabled) return;
        while(active.get()<limit) {
            var task=claim(); if(task==null) return;
            active.incrementAndGet();
            try { workers.execute(()-> {
                try { workflow.finished(task,agents.execute(task)); }
                catch(BudgetService.RateLimited e) {
                    store.update("tasks.defer",task.get("id"));
                    store.event((String)task.get("novel_id"),(String)task.get("id"),"RATE_WAIT",e.getMessage());
                }
                catch(Exception e) { log.warn("任务 {} 执行失败：{}",task.get("id"),e.getMessage()); fail(task,e); }
                finally { active.decrementAndGet(); }
            }); } catch(TaskRejectedException rejected) {
                active.decrementAndGet();
                store.update("tasks.wait",task.get("id"));
                throw rejected;
            }
        }
    }
    private Map<String,Object> claim() {
        return store.transaction(() -> {
            var task=dispatchMapper.nextCandidate();
            if(task==null) return null;
            int count=dispatchMapper.claim((String)task.get("id"));
            if(count!=1) return null;
            store.event((String)task.get("novel_id"),(String)task.get("id"),"RUNNING",(String)task.get("kind"));
            return task;
        });
    }
    private void fail(Map<String,Object> task,Exception error) {
        store.transaction(() -> {
            String novelId=(String)task.get("novel_id");
            if(novelId!=null) store.lockNovel(novelId);
            if(!"RUNNING".equals(store.one("tasks.find",task.get("id")).get("status"))) return null;
            String message=Objects.toString(error.getMessage(),error.getClass().getSimpleName());
            store.update("tasks.fail",message,task.get("id"));
            if(novelId!=null) store.update("novels.fail",
                    error instanceof BudgetService.BudgetExceeded?"BUDGET_PAUSED":"ERROR",message,novelId);
            store.event(novelId,(String)task.get("id"),"ERROR",message);
            return null;
        });
    }
    public Map<String,Object> status() { return Map.of("workers",limit,"active",active.get(),"ready",ready); }
    @PreDestroy public void close() { ready=false; }
}
