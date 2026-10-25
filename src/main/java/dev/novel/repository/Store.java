package dev.novel.repository;

import dev.novel.support.Json;
import dev.novel.support.TaskLogFormatter;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

@Repository
public class Store {
    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private final SqlSessionTemplate db;
    private final TransactionTemplate tx;
    private final Json json;
    private final TaskLogFormatter logs;

    public Store(SqlSessionTemplate db, TransactionTemplate tx, Json json, TaskLogFormatter logs) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.logs = logs;
    }

    public <T> T transaction(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    private Map<String, Object> parameters(Object[] args) {
        var parameters = new HashMap<String, Object>();
        parameters.put("args", args);
        parameters.put("userId", dev.novel.security.UserScope.current());
        parameters.put("owner", dev.novel.security.UserScope.owner());
        return parameters;
    }

    public int update(String statement, Object... args) {
        return db.update(statement, parameters(args));
    }

    public List<Map<String, Object>> list(String statement, Object... args) {
        return db.selectList(statement, parameters(args));
    }

    public Map<String, Object> one(String statement, Object... args) {
        var rows = list(statement, args);
        if (rows.isEmpty()) throw new NoSuchElementException("记录不存在");
        return rows.get(0);
    }

    public Map<String, Object> novel(String id) {
        return one("novels.find", id);
    }

    public Map<String, Object> lockNovel(String id) {
        return one("novels.lock", id);
    }

    public void event(String novel, String task, String type, String message) {
        event(novel, task, type, message, null);
    }

    public void event(String novel, String task, String type, String message, String execution) {
        update("events.create", novel, task, type, message, execution);
        Runnable output;
        if (logs.detail(type)) {
            output = () -> log.debug("event={} taskId={} executionId={} novelId={}", type, task, execution, novel);
        } else {
            var context = task == null ? null : one("tasks.logContext", task);
            String label = logs.label(context, task, novel);
            String text = logs.event(type, message, context == null ? "" : string(context, "kind"));
            output = () -> {
                if (type.equals("ERROR")) log.error("{} {}", label, text);
                else if (type.equals("RATE_WAIT")) log.warn("{} {}", label, text);
                else log.info("{} {}", label, text);
            };
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务成功后输出业务日志，数据库提交失败不会显示任务已经完成。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    output.run();
                }
            });
        } else output.run();
    }

    public void logProgress(String task, String stage, Object elapsed, Object modelElapsed) {
        var context = one("tasks.logContext", task);
        log.info("{} {}", logs.label(context, task, null), logs.progress(stage, elapsed, modelElapsed));
    }

    public String enqueue(String novel, String kind, int chapter, int revision, long version, String key, Object payload) {
        String owner = novel == null ? dev.novel.security.UserScope.owner() : string(novel(novel), "user_id");
        var existing = list("tasks.byKey", key);
        if (!existing.isEmpty()) return (String) existing.get(0).get("id");
        String id = UUID.randomUUID().toString();
        update("tasks.create",
                id, novel, kind, key, chapter, revision, version, json.write(payload), owner);
        event(novel, id, "ENQUEUED", kind);
        return id;
    }

    public static int integer(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    public static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    public static String string(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }
}
