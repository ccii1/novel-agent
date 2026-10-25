package dev.novel.engine;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.service.AgentService;
import dev.novel.service.WorkflowService;
import dev.novel.service.BudgetService;
import dev.novel.service.ObservationService;
import dev.novel.service.TaskProgressService;
import dev.novel.security.UserScope;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TaskEngine {
    private final Store store;
    private final TaskDispatchMapper dispatchMapper;
    private final ModelCallMapper calls;
    private final AgentService agents;
    private final WorkflowService workflow;
    private final ObservationService observations;
    private final TaskProgressService progress;
    private final AsyncTaskExecutor workers;
    private final int limit;
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean ready;
    @Value("${novel.engine-enabled:true}")
    private boolean enabled;

    public TaskEngine(Store store, TaskDispatchMapper dispatchMapper, ModelCallMapper calls,
                      AgentService agents, WorkflowService workflow, ObservationService observations, TaskProgressService progress,
                      @Qualifier("novelTaskExecutor") AsyncTaskExecutor workers, @Value("${novel.workers}") int limit) {
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("workers 应为1至32");
        this.store = store;
        this.agents = agents;
        this.workflow = workflow;
        this.limit = limit;
        this.dispatchMapper = dispatchMapper;
        this.calls = calls;
        this.workers = workers;
        this.observations = observations;
        this.progress = progress;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // 当前恢复逻辑面向单实例，未知调用先核对费用，不能直接发送新的模型请求。
        store.transaction(() -> {
            observations.recover();
            calls.recoverInterrupted();
            store.update("modelTurns.recover");
            for (var task : store.list("tasks.running")) {
                if (calls.uncertainTaskCount(Store.string(task, "id")) == 0) {
                    store.update("tasks.wait", task.get("id"));
                } else {
                    fail(task, new IllegalStateException("服务中断期间调用结果不确定，请核对费用"));
                }
            }
            for (var novel : store.list("novels.checking")) {
                var chapter = store.one("chapters.find", novel.get("id"), novel.get("chapter_no"));
                workflow.ensureReviewTasks(novel, Store.integer(novel, "chapter_no"), Store.integer(chapter, "revision"));
            }
            store.event(null, null, "RECOVERY", "已恢复持久化任务，结果不确定的调用等待人工核对");
            return null;
        });
        ready = true;
    }

    @Scheduled(fixedDelay = 500)
    public void dispatch() {
        if (!ready || !enabled) return;
        while (active.get() < limit) {
            var task = claim();
            if (task == null) return;
            active.incrementAndGet();
            try {
                workers.execute(() -> executeTask(task));
            } catch (TaskRejectedException rejected) {
                active.decrementAndGet();
                store.transaction(() -> {
                    store.update("tasks.wait", task.get("id"));
                    observations.finish(task, "REJECTED", null, "工作线程未接受任务");
                    return null;
                });
                throw rejected;
            }
        }
    }

    private void executeTask(Map<String, Object> task) {
        try {
            // HTTP 请求已经结束，用户范围必须从持久化任务恢复。
            UserScope.enter(Store.string(task, "user_id"));
            var result = agents.execute(task);
            workflow.finished(task, result);
        } catch (BudgetService.RateLimited limited) {
            deferTask(task, limited.getMessage());
        } catch (Exception failure) {
            fail(task, failure);
        } finally {
            // 线程会被其他用户复用，任何成功或失败路径都需要清理身份。
            UserScope.clear();
            active.decrementAndGet();
        }
    }

    private void deferTask(Map<String, Object> task, String message) {
        store.transaction(() -> {
            store.update("tasks.defer", task.get("id"));
            observations.finish(task, "RATE_WAIT", null, message);
            store.event((String) task.get("novel_id"), Store.string(task, "id"), "RATE_WAIT", message);
            return null;
        });
    }

    private Map<String, Object> claim() {
        return store.transaction(() -> {
            var task = dispatchMapper.nextCandidate();
            if (task == null) return null;
            // 候选查询不代表已经认领，条件更新成功后才能创建执行记录。
            String taskId = Store.string(task, "id");
            if (dispatchMapper.claim(taskId) != 1) return null;
            observations.start(task);
            store.event((String) task.get("novel_id"), taskId, "RUNNING",
                    Store.string(task, "kind"), (String) task.get("execution_id"));
            return task;
        });
    }

    private void fail(Map<String, Object> task, Exception error) {
        store.transaction(() -> {
            String novelId = (String) task.get("novel_id");
            String taskId = Store.string(task, "id");
            if (novelId != null) store.lockNovel(novelId);
            if (!"RUNNING".equals(store.one("tasks.find", taskId).get("status"))) return null;

            String message = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            observations.finish(task, "ERROR", null, message);
            store.update("tasks.fail", message, taskId);
            // 独立建议任务失败时，作品当前的创作流程继续保留自己的状态。
            if (novelId != null && !Set.of("PLAN_CHANGE", "COORDINATE").contains(task.get("kind"))) {
                String status = error instanceof BudgetService.BudgetExceeded ? "BUDGET_PAUSED" : "ERROR";
                store.update("novels.fail", status, message, novelId);
            }
            store.event(novelId, taskId, "ERROR", message);
            return null;
        });
    }

    public Map<String, Object> status() {
        long visible = UserScope.current() == null ? active.get() : Store.number(store.one("tasks.activeCount"), "active");
        return Map.of("workers", limit, "active", visible, "ready", ready);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void logRunning() {
        if (!ready || !enabled) return;
        for (var running : store.list("tasks.running")) {
            var snapshot = progress.progress(Store.string(running, "id"));
            var task = (Map<?, ?>) snapshot.get("task");
            if (!"RUNNING".equals(task.get("status"))) continue;
            store.logProgress(Store.string(running, "id"), snapshot.get("stage").toString(),
                    task.get("elapsed_ms"), task.get("model_elapsed_ms"));
        }
    }

    @PreDestroy
    public void close() {
        ready = false;
    }
}
