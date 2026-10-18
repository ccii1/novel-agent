package dev.novel.repository;

import dev.novel.support.Json;

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
    private static final Logger log=LoggerFactory.getLogger(Store.class);
    private static final Set<String> LOG_METADATA=Set.of("AGENT_STARTED","AGENT_FINISHED","MODEL_REQUEST_SENT",
            "MODEL_RESPONSE_RECEIVED","MODEL_USAGE_RECORDED","RESEARCH_INPUT_READY","RESEARCH_OUTPUT_VALIDATED");
    private final SqlSessionTemplate db;
    private final TransactionTemplate tx;
    private final Json json;
    public Store(SqlSessionTemplate db, TransactionTemplate tx, Json json) { this.db = db; this.tx = tx; this.json = json; }
    public <T> T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
    public int update(String statement,Object... args) { return db.update(statement,Map.of("args",args)); }
    public List<Map<String,Object>> list(String statement, Object... args) { return db.selectList(statement,Map.of("args",args)); }
    public Map<String,Object> one(String statement, Object... args) {
        var rows = list(statement, args);
        if (rows.isEmpty()) throw new NoSuchElementException("记录不存在");
        return rows.get(0);
    }
    public Map<String,Object> novel(String id) { return one("novels.find", id); }
    public Map<String,Object> lockNovel(String id) { return one("novels.lock", id); }
    public void event(String novel, String task, String type, String message) {
        event(novel,task,type,message,null);
    }
    public void event(String novel,String task,String type,String message,String execution) {
        update("events.create",novel,task,type,message,execution);
        Runnable output=()->log.info("event={} taskId={} executionId={} novelId={} metadata={}",
                type,task,execution,novel,LOG_METADATA.contains(type)?message:"");
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { output.run(); }
            });
        } else output.run();
    }
    public String enqueue(String novel, String kind, int chapter, int revision, long version, String key, Object payload) {
        var existing = list("tasks.byKey", key);
        if (!existing.isEmpty()) return (String) existing.get(0).get("id");
        String id = UUID.randomUUID().toString();
        update("tasks.create",
                id, novel, kind, key, chapter, revision, version, json.write(payload));
        event(novel, id, "ENQUEUED", kind);
        return id;
    }
    public static int integer(Map<String,Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    public static long number(Map<String,Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    public static String string(Map<String,Object> row, String key) { return Objects.toString(row.get(key), ""); }
}
