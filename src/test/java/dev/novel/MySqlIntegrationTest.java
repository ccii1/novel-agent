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
    @Autowired dev.novel.service.PlanChangeService planChanges;

    @Test void mysqlOwnershipMigrationScopesListsMetricsAndMemoryAccess() {
        dev.novel.security.UserScope.enter("mysql_user_one");
        String first;
        try {
            first=projects.create("用户一作品","调查失踪信件","保持人物状态",1,BigDecimal.TEN,BigDecimal.ONE);
            assertThat(repository.novel(first).get("user_id")).isEqualTo("mysql_user_one");
            assertThat(repository.one("tasks.byNovel",first).get("user_id")).isEqualTo("mysql_user_one");
        } finally { dev.novel.security.UserScope.clear(); }
        dev.novel.security.UserScope.enter("mysql_user_two");
        try {
            String second=projects.create("用户二作品","调查失踪信件","保持人物状态",1,BigDecimal.TEN,BigDecimal.ONE);
            assertThat(repository.list("novels.all")).extracting(row->row.get("id")).containsExactly(second);
            assertThatThrownBy(()->repository.novel(first)).isInstanceOf(java.util.NoSuchElementException.class);
            var dashboard=observations.dashboard(null,null,null,null,0);
            assertThat(((Number)dashboard.get("total")).longValue()).isEqualTo(1);
            assertThat((java.util.List<?>)dashboard.get("tasks")).hasSize(1);
        } finally { dev.novel.security.UserScope.clear(); }
    }

    @Test void mysqlPersistsPartialOutlineRequestAndOriginalVersion() {
        String novel=projects.create("大纲版本验证","调查城门封条","保留人物判断",1,BigDecimal.TEN,BigDecimal.ONE);
        repository.update("tasks.cancelNovel",novel);
        var plan=json.read("{\"stageGoal\":\"核对封条\",\"coreMilestones\":[\"辨认来源\"],\"chapters\":[{\"number\":1,\"title\":\"城门\",\"goal\":\"核对封条\",\"characters\":[\"陆禾\"],\"foreshadowIds\":[\"seal\"]}]}");
        repository.update("novels.awaitPlan",json.write(plan),novel);
        String change=planChanges.request(novel,"/chapters/0/goal","延迟揭示封条来源");
        var stored=repository.one("planChanges.find",change);
        assertThat(stored).containsEntry("status","GENERATING").containsEntry("base_plan",json.write(plan));
        assertThat(repository.one("tasks.find",stored.get("task_id"))).containsEntry("kind","PLAN_CHANGE");
        assertThat(repository.novel(novel).get("plan")).isEqualTo(json.write(plan));
    }

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
        var snapshot=repository.one("novels.snapshot",novel);
        assertThat(snapshot.get("status")).isEqualTo("PLANNING");
        assertThat(((Number)snapshot.get("event_cursor")).longValue()).isPositive();
        assertThat(repository.list("novels.summaries").stream().filter(row -> novel.equals(row.get("id"))).findFirst().orElseThrow())
                .containsEntry("title","修理铺").doesNotContainKeys("bible","premise");
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
