package dev.novel;

import dev.novel.integration.model.ModelCatalog;
import dev.novel.repository.Store;
import dev.novel.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named="NOVEL_LOCAL_WORKFLOW_TEST",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "novel.engine-enabled=true","novel.redis.enabled=false","novel.default-provider=default",
        "novel.model.billing-mode=unmetered","logging.charset.console=UTF-8"})
class LiveWorkflowTest {
    private static final String DATABASE="jdbc:h2:file:./.work/live-workflow-"+UUID.randomUUID()+";DB_CLOSE_ON_EXIT=FALSE";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url",()->DATABASE); }
    @Autowired ProjectService projects;
    @Autowired Store store;
    @Autowired ModelCatalog models;
    @LocalServerPort int port;

    @Test @Timeout(1500)
    void realLocalAgentsReachStageReviewOrExplicitHumanReview() throws Exception {
        var selection=models.resolve(null,"","PLAN");
        var endpoint=URI.create(selection.endpoint());
        assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
        assertThat(endpoint.getPort()).isEqualTo(8317);
        assertThat(selection.billingMode()).isEqualTo("unmetered");
        assertThat(selection.ready()).isTrue();
        String novel=projects.create("旧城修理铺的失物单","原创都市职业悬疑：修理师从一张失物单发现账目异常，"+
                        "本章解决一个具体疑点，并留下下一章可继续调查的线索。",
                "第三人称。主角林舟、店员沈月。保持人物知情范围，采用动作和线索推进；"+
                        "本阶段只有一章，正文目标2200个汉字，必须处于2000至2500个非空白字符之间。"+
                        "对话行占比控制在25%以内。只引入少量可以在本章合理说明的设定。",1,BigDecimal.TEN,BigDecimal.TEN);
        System.out.println("真实本地流程：novelId="+novel+"，数据库="+DATABASE);
        await().pollInterval(Duration.ofSeconds(2)).atMost(Duration.ofMinutes(20)).until(()-> {
            var current=store.novel(novel);
            String status=Store.string(current,"status");
            if(status.equals("AWAITING_PLAN")) { projects.approvePlan(novel,null);return false; }
            return Set.of("AWAITING_STAGE_REVIEW","AWAITING_CHANGE","AWAITING_HELP","ERROR","BUDGET_PAUSED").contains(status);
        });
        var completed=store.novel(novel);
        System.out.println("真实本地流程结束：status="+completed.get("status")+"，memoryVersion="+completed.get("memory_version"));
        assertThat(completed.get("status")).as("真实调用发生错误时保留数据库与错误依据：%s",completed.get("error"))
                .isIn("AWAITING_STAGE_REVIEW","AWAITING_CHANGE","AWAITING_HELP");
        assertThat(store.list("tasks.byNovel",novel)).hasSizeGreaterThanOrEqualTo(6);
        if(completed.get("status").equals("AWAITING_STAGE_REVIEW")) {
            assertThat(Store.number(completed,"memory_version")).isEqualTo(1);
            assertThat(store.one("chapters.find",novel,1).get("status")).isEqualTo("ACCEPTED");
        }
        var requests=AuthHttp.owner(port);
        for(String path:new String[]{"/novels/"+novel,"/novels/"+novel+"/chapters/1",
                "/novels/"+novel+"/memories","/novels/"+novel+"/graph","/novels/"+novel+"/quality",
                "/novels/"+novel+"/truth-files","/novels/"+novel+"/truth-files/export","/observability?novelId="+novel}) {
            var response=requests.request("/api"+path,null);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
        }
    }
}
