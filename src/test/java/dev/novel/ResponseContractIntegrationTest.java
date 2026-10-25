package dev.novel;

import dev.novel.dto.response.NovelResponses.NovelResponse;
import dev.novel.repository.Store;
import dev.novel.service.BudgetService;
import dev.novel.support.Json;
import dev.novel.support.ResponseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:response-contract;DB_CLOSE_DELAY=-1", "novel.engine-enabled=false",
        "novel.model.reasoning-efforts[0]=low", "novel.model.reasoning-efforts[1]=high"})
class ResponseContractIntegrationTest {
    @LocalServerPort int port;
    @Autowired Store store;
    @Autowired Json json;
    @Autowired ResponseMapper responses;
    @Autowired BudgetService budget;
    @Autowired dev.novel.repository.mapper.ModelCallMapper calls;

    private String create(AuthHttp http) throws Exception {
        var response = http.request("/api/novels", """
                {"title":"响应结构验证","premise":"调查失踪信件","bible":"保留人物信息边界",
                 "stageSize":1,"novelBudget":10,"chapterBudget":1}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.read(response.body()).size()).isEqualTo(1);
        return json.read(response.body()).path("id").asText();
    }

    @Test void httpPersistsReasoningChoiceAndRejectsUnsupportedLevels() throws Exception {
        var http=AuthHttp.owner(port);
        var created=http.request("/api/novels", """
                {"title":"思考强度验证","premise":"调查信件","bible":"保持人物状态",
                 "stageSize":1,"novelBudget":10,"chapterBudget":1,"provider":"default","reasoningEffort":"high"}
                """);
        assertThat(created.statusCode()).isEqualTo(200);
        String id=json.read(created.body()).path("id").asText();
        var detail=json.read(http.request("/api/novels/"+id,null).body());
        assertThat(detail.path("novel").path("reasoning_effort").asText()).isEqualTo("high");
        var research=http.request("/api/research", "{\"brief\":\"调查信件\",\"reasoningEffort\":\"low\"}");
        assertThat(research.statusCode()).isEqualTo(200);
        String taskId=json.read(research.body()).path("id").asText();
        var task=json.read(http.request("/api/observability/tasks/"+taskId,null).body());
        assertThat(task.path("task").path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(http.request("/api/research", "{\"brief\":\"调查信件\",\"reasoningEffort\":\"invalid\"}").statusCode()).isEqualTo(400);
    }

    @Test void preservesFieldNamesAndProjectsOnlyDeclaredDatabaseColumns() throws Exception {
        var http = AuthHttp.owner(port);
        String id = create(http);
        var source = new LinkedHashMap<>(store.novel(id));
        source.put("internal_marker", "not-an-api-field");
        var projected = json.tree(responses.to(source, NovelResponse.class));
        assertThat(projected.has("internal_marker")).isFalse();
        assertThat(projected.has("stage_no")).isTrue();
        assertThat(projected.has("stageNo")).isFalse();
        assertThat(projected.path("novel_budget").decimalValue()).isEqualByComparingTo("10");

        var detail = json.read(http.request("/api/novels/" + id, null).body());
        assertThat(detail.path("novel").path("id").asText()).isEqualTo(id);
        assertThat(detail.path("novel").path("created_at").isTextual()).isTrue();
        assertThat(detail.path("tasks").get(0).path("payload").isTextual()).isTrue();
        assertThat(detail.path("memoryReview").path("pending").asBoolean()).isFalse();
        var summaries = json.read(http.request("/api/novels/summaries", null).body());
        summaries.forEach(row -> assertThat(row.has("bible")).isFalse());
    }

    @Test void preservesAccountingPrecisionNullUsageAndObservationFields() throws Exception {
        var http = AuthHttp.owner(port);
        String id = create(http);
        var task = store.one("tasks.byNovel", id);
        // 验证实际预算预留记录的接口表示，不发送模型请求。
        budget.reserve(task, new BigDecimal("0.123456"), "accounting-validation", BigDecimal.ONE, BigDecimal.ONE);
        String callId = calls.overview().stream().filter(row -> row.get("task_id").equals(task.get("id")))
                .findFirst().orElseThrow().get("id").toString();
        var response = http.request("/api/calls/" + callId, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.read(response.body());
        assertThat(body.path("reserved").decimalValue()).isEqualByComparingTo("0.123456");
        assertThat(body.get("actual").isNull()).isTrue();
        assertThat(body.get("input_tokens").isNull()).isTrue();

        var dashboard = json.read(http.request("/api/observability?novelId=" + id, null).body());
        assertThat(dashboard.path("summary").get(0).path("pending_reservation").decimalValue()).isEqualByComparingTo("0.123456");
        assertThat(dashboard.path("tasks").get(0).path("call_id").asText()).isEqualTo(callId);
        var detail = json.read(http.request("/api/observability/tasks/" + task.get("id"), null).body());
        assertThat(detail.path("call").path("reserved").decimalValue()).isEqualByComparingTo("0.123456");
        assertThat(detail.path("turns").isArray()).isTrue();
        var progress = json.read(http.request("/api/observability/tasks/" + task.get("id") + "/progress", null).body());
        assertThat(progress.path("task").path("task_age_ms").isNumber()).isTrue();
        assertThat(progress.path("pollAfterMs").asInt()).isEqualTo(5000);
    }

    @Test void preservesDynamicOutlineValuesAndGraphAndQualityShapes() throws Exception {
        var http = AuthHttp.owner(port);
        String id = create(http);
        store.update("tasks.cancelNovel", id);
        store.update("novels.awaitPlan", """
                {"stageGoal":"调查信件","coreMilestones":["核对签名"],
                 "chapters":[{"number":1,"title":"信件","goal":"核对笔迹","characters":["陆禾"],"foreshadowIds":[]}]}
                """, id);
        var plan = json.read(http.request("/api/novels/" + id + "/plan-changes", null).body());
        assertThat(plan.path("targets").get(0).path("value").isTextual()).isTrue();
        assertThat(plan.path("targets").get(1).path("value").isArray()).isTrue();
        var created = http.request("/api/novels/" + id + "/plan-changes",
                "{\"path\":\"/stageGoal\",\"instructions\":\"保留身份悬念\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        var changes = json.read(http.request("/api/novels/" + id + "/plan-changes", null).body());
        assertThat(changes.path("changes").get(0).path("target_label").asText()).isEqualTo("阶段目标");
        var graph = json.read(http.request("/api/novels/" + id + "/graph", null).body());
        assertThat(graph.path("nodes").isArray()).isTrue();
        assertThat(graph.path("edges").isArray()).isTrue();
        var quality = json.read(http.request("/api/novels/" + id + "/quality", null).body());
        assertThat(quality.path("totals").path("stored_characters").isNumber()).isTrue();
    }

    @Test void exposesTypedCatalogueAndConciseValidationErrors() throws Exception {
        var http = AuthHttp.owner(port);
        var agents = json.read(http.request("/api/agents", null).body());
        assertThat(agents.size()).isEqualTo(6);
        assertThat(agents.get(0).path("runtime").path("maxIterations").asInt()).isPositive();
        assertThat(agents.get(0).path("runtime").path("tools").isArray()).isTrue();
        var invalid = http.request("/api/novels", "{}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json.read(invalid.body()).path("error").asText()).contains("title")
                .doesNotContain("MethodArgumentNotValidException", "org.springframework");
        var success = http.request("/api/auth/logout", "{}");
        assertThat(json.read(success.body()).path("ok").asBoolean()).isTrue();
        assertThat(http.request("/api/auth/me", null).statusCode()).isEqualTo(401);
    }
}
