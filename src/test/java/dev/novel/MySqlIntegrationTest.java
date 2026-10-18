package dev.novel;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.ObservationService;
import dev.novel.service.TaskProgressService;
import dev.novel.service.ChapterQualityService;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_MYSQL_TEST",matches="true")
@ActiveProfiles("mysql")
class MySqlIntegrationTest extends DomainIntegrationTest {
    private static final String DATABASE="novel_agent_test_"+UUID.randomUUID().toString().replace("-","");
    @Autowired Store repository;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired ObservationService observations;
    @Autowired TaskProgressService progress;
    @Autowired ChapterQualityService quality;

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

    @Test void mysqlPersistsDraftRevisionsTaskTimingAndObservationFeedback() {
        String novel=projects.create("修理铺","追查失物单","保持知情范围",1,BigDecimal.TEN,BigDecimal.ONE);
        var task=repository.one("tasks.byNovel",novel);
        task.put("queued_at",task.get("created_at"));
        repository.transaction(()-> {
            assertThat(dispatch.claim(Store.string(task,"id"))).isEqualTo(1);
            observations.start(task);return null;
        });
        var running=(Map<?,?>)progress.progress(Store.string(task,"id")).get("task");
        assertThat(((Number)running.get("elapsed_ms")).longValue()).isGreaterThanOrEqualTo(0);
        repository.update("chapters.draft",novel,1,1,0,"失物单","林舟找到失物单。",0);
        repository.update("chapters.draft",novel,1,1,1,"失物单","林舟核对失物单。",0);
        assertThat(repository.one("chapters.find",novel,1).get("revision")).isEqualTo(1);
        quality.record(novel,1,0,"林舟找到失物单。");
        quality.record(novel,1,1,"林舟核对失物单。");
        var result=json.read("{\"approved\":true,\"issues\":[],\"majorChange\":false}");
        repository.transaction(()-> {
            repository.update("tasks.defer",task.get("id"));
            assertThat(repository.one("tasks.find",task.get("id")).get("status")).isEqualTo("WAITING");
            repository.update("tasks.done",json.write(result),task.get("id"));
            observations.finish(task,"DONE",result,null);return null;
        });
        observations.feedback(Store.string(task,"id"),4,"检查依据可以追溯");
        var detail=observations.detail(Store.string(task,"id"));
        assertThat((java.util.List<?>)detail.get("executions")).hasSize(1);
        assertThat((java.util.List<?>)detail.get("feedback")).hasSize(1);
        assertThat(observations.dashboard(novel,null,null,null,0).get("storage")).isEqualTo("MySQL 业务数据库");
        assertThat(((Map<?,?>)progress.progress(Store.string(task,"id")).get("task")).get("elapsed_ms")).isNotNull();
    }
}
