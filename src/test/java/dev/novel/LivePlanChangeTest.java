package dev.novel;

import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.integration.model.ModelCatalog;
import dev.novel.support.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_PLAN_CHANGE_LIVE",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:file:./.work/plan-change-qa;DB_CLOSE_ON_EXIT=FALSE","novel.engine-enabled=false","novel.redis.enabled=false"})
class LivePlanChangeTest {
    @Autowired TestDatabase db;
    @Autowired Store store;
    @Autowired ProjectService projects;
    @Autowired PlanChangeService changes;
    @Autowired AgentService agents;
    @Autowired WorkflowService workflow;
    @Autowired ModelCatalog catalog;
    @Autowired Json json;
    @Test @Timeout(300) void realModelProducesReviewablePersistedPartialChange() throws Exception {
        assertThat(catalog.resolve("local","gpt-5.6-terra","PLAN_CHANGE").billingMode()).isEqualTo("unmetered");
        var recorded=json.read(Files.readString(Path.of(".work/combined-review-case.json")));
        var plan=recorded.path("plan");
        String id=projects.create("局部大纲修改验证",recorded.path("premise").asText(),recorded.path("bible").asText(),plan.path("chapters").size(),BigDecimal.TEN,BigDecimal.ONE,"local","gpt-5.6-terra");
        store.update("tasks.cancelNovel",id);
        db.update("UPDATE novels SET status='AWAITING_CHANGE',chapter_no=?,plan=? WHERE id=?",recorded.path("chapter").asInt(),json.write(plan),id);
        int first=recorded.path("chapter").asInt();
        int index=java.util.stream.IntStream.range(0,plan.path("chapters").size()).filter(value->plan.path("chapters").get(value).path("number").asInt()==first).findFirst().orElseThrow();
        store.update("chapters.draft",id,first,1,0,recorded.path("title").asText(),recorded.path("body").asText(),0);
        store.update("chapters.accept",recorded.path("delta").path("summary").asText(),0,id,first);
        String changeId=changes.request(id,"/chapters/"+index+"/foreshadowIds","保留本章已有伏笔，再增加一个后续核对封条来源的伏笔标识，不提前揭示答案，并说明对后续章节的影响。");
        var change=store.one("planChanges.find",changeId);
        db.update("UPDATE tasks SET status='RUNNING' WHERE id=?",change.get("task_id"));
        var task=store.one("tasks.find",change.get("task_id"));
        var result=agents.execute(task);
        workflow.finished(task,result);
        assertThat(store.one("planChanges.find",changeId)).containsEntry("status","PENDING");
        assertThat(json.read(Store.string(store.novel(id),"plan"))).isEqualTo(plan);
        assertThatThrownBy(()->changes.approve(id,changeId,false,false)).hasMessageContaining("核心剧情");
        assertThatThrownBy(()->changes.approve(id,changeId,true,false)).hasMessageContaining("已完成章节");
        var calls=db.list("SELECT input_tokens,output_tokens,actual,latency_ms FROM model_calls WHERE novel_id=?",id);
        assertThat(calls).hasSize(1).allSatisfy(call->assertThat((BigDecimal)call.get("actual")).isEqualByComparingTo(BigDecimal.ZERO));
        Files.writeString(Path.of(".work/plan-change-live-result.json"),json.write(Map.of("id",id,"changeId",changeId,"before",plan,"body",recorded.path("body"),"firstChapter",first,"result",result,"calls",calls)));
    }
}
