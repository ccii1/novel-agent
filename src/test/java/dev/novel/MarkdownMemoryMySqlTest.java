package dev.novel;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="NOVEL_MYSQL_TEST",matches="true")
@ActiveProfiles("mysql")
class MarkdownMemoryMySqlTest extends MarkdownMemoryIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        MySqlTestDatabase.configure(registry);
    }
    @org.junit.jupiter.api.AfterAll static void releaseDatabase() throws Exception { MySqlTestDatabase.release(); }
}
