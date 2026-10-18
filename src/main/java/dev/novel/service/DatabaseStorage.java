package dev.novel.service;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

@Service
public class DatabaseStorage {
    private final String kind;
    public DatabaseStorage(SqlSessionFactory sessions) { kind=sessions.getConfiguration().getDatabaseId(); }
    public String kind() { return kind; }
    public String description() {
        return switch(kind) {
            case "mysql" -> "MySQL 业务数据库";
            case "h2" -> "H2 文件数据库 data/novel.mv.db";
            default -> "关系数据库";
        };
    }
}
