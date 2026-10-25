package dev.novel.tools;

import org.flywaydb.core.Flyway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

public class MigrateH2ToMySql {
    // 迁移业务数据；budget_lock 是目标 schema 初始化的互斥记录。
    private static final List<String> TABLES = List.of("app_users", "novels", "tasks", "chapters", "chapter_history", "memories",
            "model_calls", "model_call_turns", "chapter_metrics", "research", "research_messages", "edit_requests", "events",
            "agent_executions", "agent_feedback", "plan_changes", "memory_file_versions", "memory_files");

    public static void main(String[] args) throws Exception {
        var configuration = new Properties();
        try (var input = Files.newInputStream(Path.of("config/mysql-local.properties"))) {
            configuration.load(input);
        }
        String url = Objects.requireNonNull(configuration.getProperty("spring.datasource.url"));
        String username = Objects.requireNonNull(configuration.getProperty("spring.datasource.username"));
        String password = Objects.requireNonNull(configuration.getProperty("spring.datasource.password"));
        String sourcePassword = Objects.toString(System.getenv("DATABASE_PASSWORD"), "");
        // H2 只读连接会拒绝访问正在被服务占用的文件。
        try (var source = DriverManager.getConnection("jdbc:h2:file:./data/novel;IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", sourcePassword)) {
            try (var target = DriverManager.getConnection(url, username, password)) {
                if (!"novel_agent".equals(target.getCatalog()))
                    throw new IllegalStateException("目标数据库必须为 novel_agent");
                Flyway.configure().dataSource(url, username, password).locations("filesystem:src/main/resources/db/migration/mysql")
                        .cleanDisabled(true).load().migrate();
                copyBusinessData(source, target);
                System.out.println("全部业务数据已提交到 MySQL，H2 源文件保持只读。");
            }
        }
    }

    // 调用方独占并关闭目标连接；失败时未提交的数据由数据库撤销。
    public static void copyBusinessData(Connection source, Connection target) throws SQLException {
        if (!target.getAutoCommit()) throw new IllegalStateException("迁移需要独立的目标数据库连接");
        for (String table : TABLES) {
            if (count(target, table) != 0) throw new IllegalStateException("目标表已有数据，迁移终止：" + table);
        }
        var tables = new ArrayList<>(TABLES);
        boolean versions = exists(source, "memory_file_versions"), files = exists(source, "memory_files");
        if (versions != files) throw new IllegalStateException("源数据库的记忆文件版本表不完整");
        if (!versions) tables.removeAll(List.of("memory_file_versions", "memory_files"));
        target.setAutoCommit(false);
        for (String table : tables) {
            long rows = copy(source, target, table);
            if (rows != count(source, table) || rows != count(target, table))
                throw new IllegalStateException("迁移数量校验失败：" + table);
            System.out.println(table + "：已复制并核对 " + rows + " 条记录");
        }
        target.commit();
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (var rows = connection.getMetaData().getTables(null, null, null, null)) {
            while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
            return false;
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long copy(Connection source, Connection target, String table) throws SQLException {
        try (var statement = source.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
            var metadata = rows.getMetaData();
            int size = metadata.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int index = 1; index <= size; index++)
                columns.add("`" + metadata.getColumnName(index).toLowerCase(Locale.ROOT) + "`");
            String sql = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES (" + String.join(",", Collections.nCopies(size, "?")) + ")";
            try (var insert = target.prepareStatement(sql)) {
                long count = 0;
                while (rows.next()) {
                    for (int index = 1; index <= size; index++) {
                        Object value = rows.getObject(index);
                        if (value instanceof Clob) insert.setString(index, rows.getString(index));
                        else insert.setObject(index, value);
                    }
                    insert.addBatch();
                    count++;
                    if (count % 100 == 0) insert.executeBatch();
                }
                if (count % 100 != 0) insert.executeBatch();
                return count;
            }
        }
    }
}
