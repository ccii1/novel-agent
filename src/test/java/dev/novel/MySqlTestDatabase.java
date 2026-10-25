package dev.novel;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import org.springframework.test.context.DynamicPropertyRegistry;

final class MySqlTestDatabase {
    private static Connection lease;
    static synchronized void configure(DynamicPropertyRegistry registry) throws Exception {
        var properties=new Properties();
        try(var input=Files.newInputStream(Path.of("config/mysql-local.properties"))) { properties.load(input); }
        var source=URI.create(properties.getProperty("spring.datasource.url").substring("jdbc:".length()));
        String url="jdbc:"+source.resolve("/novel_agent_test?"+source.getRawQuery());
        if(lease!=null) throw new IllegalStateException("MySQL 测试资源已被当前进程使用");
        lease=DriverManager.getConnection("jdbc:"+source.resolve("/information_schema?"+source.getRawQuery()),
                properties.getProperty("spring.datasource.username"),properties.getProperty("spring.datasource.password"));
        boolean configured=false;
        try {
        try(var statement=lease.prepareStatement("SELECT GET_LOCK('novel_agent_test',0)")) {
            try(var result=statement.executeQuery()) {
                if(!result.next() || result.getInt(1)!=1) {
                    throw new IllegalStateException("另一个测试进程正在使用 novel_agent_test");
                }
            }
        }
            org.flywaydb.core.Flyway.configure().dataSource(url,properties.getProperty("spring.datasource.username"),
                    properties.getProperty("spring.datasource.password")).schemas("novel_agent_test").defaultSchema("novel_agent_test")
                    .cleanDisabled(false).load().clean();
            configured=true;
        } finally { if(!configured) release(); }
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->properties.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password",()->properties.getProperty("spring.datasource.password"));
        registry.add("novel.redis.enabled",()->false);
    }
    static synchronized void release() throws Exception {
        if(lease!=null) { lease.close();lease=null; }
    }
    private MySqlTestDatabase() {}
}
