package dev.novel;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named="NOVEL_MYSQL_TEST",matches="true")
@ActiveProfiles("mysql")
class MarkdownMemoryMySqlTest extends MarkdownMemoryIntegrationTest {
    private static final String DATABASE="novel_markdown_test_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        var properties=new Properties();
        try(var input=Files.newInputStream(Path.of("config/mysql-local.properties"))) { properties.load(input); }
        var source=URI.create(properties.getProperty("spring.datasource.url").substring("jdbc:".length()));
        String url="jdbc:"+source.resolve("/"+DATABASE+"?"+source.getRawQuery());
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->properties.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password",()->properties.getProperty("spring.datasource.password"));
        registry.add("novel.redis.enabled",()->false);
    }
}
