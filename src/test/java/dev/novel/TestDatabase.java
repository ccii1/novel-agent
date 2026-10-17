package dev.novel;

import dev.novel.repository.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Clob;
import java.util.*;
import java.util.function.Supplier;

// 独立访问真实数据库，准备测试资料并核对 MyBatis 写入结果。
@Component
public class TestDatabase {
    private final JdbcTemplate jdbc;
    private final Store repository;
    public TestDatabase(JdbcTemplate jdbc,Store repository) { this.jdbc=jdbc;this.repository=repository; }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public List<Map<String,Object>> list(String sql,Object... args) {
        return jdbc.query(sql,(rs,index) -> {
            Map<String,Object> row=new LinkedHashMap<>();
            for(int column=1;column<=rs.getMetaData().getColumnCount();column++) {
                Object value=rs.getObject(column);
                if(value instanceof Clob clob) value=clob.getSubString(1,Math.toIntExact(clob.length()));
                row.put(rs.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT),value);
            }
            return row;
        },args);
    }
    public Map<String,Object> one(String sql,Object... args) { return list(sql,args).get(0); }
    public Map<String,Object> novel(String id) { return repository.novel(id); }
    public <T> T transaction(Supplier<T> action) { return repository.transaction(action); }
    public String enqueue(String novel,String kind,int chapter,int revision,long version,String key,Object payload) {
        return repository.enqueue(novel,kind,chapter,revision,version,key,payload);
    }
}
