package dev.novel.repository;

import dev.novel.support.Json;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class Store {
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
