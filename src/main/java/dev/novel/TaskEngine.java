package dev.novel;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TaskEngine {
    private final Store store;
    private final AgentService agents;
    private final WorkflowService workflow;
    private final ExecutorService workers;
    private final int limit;
    private final AtomicInteger active=new AtomicInteger();
    private final Logger log=LoggerFactory.getLogger(TaskEngine.class);
    private volatile boolean ready;
    @Value("${novel.engine-enabled:true}") private boolean enabled;
    public TaskEngine(Store store,AgentService agents,WorkflowService workflow,@Value("${novel.workers}") int limit) {
        if(limit<1 || limit>32) throw new IllegalArgumentException("workers 应为1至32");
        this.store=store;this.agents=agents;this.workflow=workflow;this.limit=limit;
        workers=Executors.newFixedThreadPool(limit);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        store.transaction(() -> {
            store.db.update("UPDATE model_calls SET status='UNCERTAIN',error='服务中断，请核对供应商费用' WHERE status='IN_FLIGHT'");
            for(var task:store.list("SELECT * FROM tasks WHERE status='RUNNING'")) {
                var uncertain=store.list("SELECT id FROM model_calls WHERE task_id=? AND status='UNCERTAIN'",task.get("id"));
                if(uncertain.isEmpty()) store.db.update("UPDATE tasks SET status='WAITING' WHERE id=?",task.get("id"));
                else fail(task,new IllegalStateException("服务中断期间调用结果不确定，请核对费用"));
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
            workers.submit(()-> {
                try { workflow.finished(task,agents.execute(task)); }
                catch(BudgetService.RateLimited e) {
                    store.db.update("UPDATE tasks SET status='WAITING',available_at=DATEADD('SECOND',5,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",task.get("id"));
                    store.event((String)task.get("novel_id"),(String)task.get("id"),"RATE_WAIT",e.getMessage());
                }
                catch(Exception e) { log.warn("任务 {} 执行失败：{}",task.get("id"),e.getMessage()); fail(task,e); }
                finally { active.decrementAndGet(); }
            });
        }
    }
    private Map<String,Object> claim() {
        return store.transaction(() -> {
            var candidates=store.list("SELECT t.* FROM tasks t LEFT JOIN novels n ON n.id=t.novel_id WHERE t.status IN ('WAITING','READY') AND t.available_at<=CURRENT_TIMESTAMP AND (t.novel_id IS NULL OR n.status IN ('PLANNING','WRITING','CHECKING','REFLECTING','EXTRACTING','CHECKING_MEMORY')) ORDER BY (SELECT COUNT(*) FROM tasks r WHERE r.status='RUNNING' AND (r.novel_id=t.novel_id OR (r.novel_id IS NULL AND t.novel_id IS NULL))),t.updated_at,t.created_at LIMIT 1");
            if(candidates.isEmpty()) return null;
            var task=candidates.get(0);
            int count=store.db.update("UPDATE tasks SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('WAITING','READY')",task.get("id"));
            if(count!=1) return null;
            store.event((String)task.get("novel_id"),(String)task.get("id"),"RUNNING",(String)task.get("kind"));
            return task;
        });
    }
    private void fail(Map<String,Object> task,Exception error) {
        store.transaction(() -> {
            String novelId=(String)task.get("novel_id");
            if(novelId!=null) store.lockNovel(novelId);
            if(!"RUNNING".equals(store.one("SELECT status FROM tasks WHERE id=?",task.get("id")).get("status"))) return null;
            String message=Objects.toString(error.getMessage(),error.getClass().getSimpleName());
            store.db.update("UPDATE tasks SET status='ERROR',error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",message,task.get("id"));
            if(novelId!=null) store.db.update("UPDATE novels SET resume_status=CASE WHEN status IN ('ERROR','BUDGET_PAUSED','PAUSED') THEN resume_status ELSE status END,status=?,error=? WHERE id=?",
                    error instanceof BudgetService.BudgetExceeded?"BUDGET_PAUSED":"ERROR",message,novelId);
            store.event(novelId,(String)task.get("id"),"ERROR",message);
            return null;
        });
    }
    public Map<String,Object> status() { return Map.of("workers",limit,"active",active.get(),"ready",ready); }
    @PreDestroy public void close() throws InterruptedException {
        ready=false;workers.shutdown();
        if(!workers.awaitTermination(15,TimeUnit.SECONDS)) workers.shutdownNow();
    }
}
