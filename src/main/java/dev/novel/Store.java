package dev.novel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

@Component
public class Store {
    final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Json json;
    private final RowMapper<Map<String,Object>> row = (rs, index) -> {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof Clob clob) value = clob.getSubString(1, Math.toIntExact(clob.length()));
            result.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), value);
        }
        return result;
    };
    public Store(JdbcTemplate db, TransactionTemplate tx, Json json) { this.db = db; this.tx = tx; this.json = json; }
    public <T> T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
    public List<Map<String,Object>> list(String sql, Object... args) { return db.query(sql, row, args); }
    public Map<String,Object> one(String sql, Object... args) {
        var rows = list(sql, args);
        if (rows.isEmpty()) throw new NoSuchElementException("记录不存在");
        return rows.get(0);
    }
    public Map<String,Object> novel(String id) { return one("SELECT * FROM novels WHERE id=?", id); }
    public Map<String,Object> lockNovel(String id) { return one("SELECT * FROM novels WHERE id=? FOR UPDATE", id); }
    public void event(String novel, String task, String type, String message) {
        db.update("INSERT INTO events(novel_id,task_id,type,message) VALUES(?,?,?,?)", novel, task, type, message);
    }
    public String enqueue(String novel, String kind, int chapter, int revision, long version, String key, Object payload) {
        var existing = list("SELECT id FROM tasks WHERE task_key=?", key);
        if (!existing.isEmpty()) return (String) existing.get(0).get("id");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO tasks(id,novel_id,kind,task_key,status,chapter_no,revision,memory_version,payload) VALUES(?,?,?,?, 'WAITING',?,?,?,?)",
                id, novel, kind, key, chapter, revision, version, json.write(payload));
        event(novel, id, "ENQUEUED", kind);
        return id;
    }
    public static int integer(Map<String,Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    public static long number(Map<String,Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    public static String string(Map<String,Object> row, String key) { return Objects.toString(row.get(key), ""); }
}
