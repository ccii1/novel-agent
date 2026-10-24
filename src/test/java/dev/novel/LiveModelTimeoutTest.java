package dev.novel;

import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.service.ProjectService;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_TIMEOUT_LIVE_TEST",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:timeout-live;DB_CLOSE_DELAY=-1",
        "novel.engine-enabled=false","novel.redis.enabled=false","novel.memory-directory=./.work/timeout-live-files",
        "novel.agent-tool-rounds.memory=1"})
class LiveModelTimeoutTest {
    private static final AtomicInteger SECONDS=new AtomicInteger(1);
    @DynamicPropertySource static void timeout(DynamicPropertyRegistry registry) {
        registry.add("novel.model.role-timeout-seconds.memory",SECONDS::get);
    }
    @Autowired ModelClient model;
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ProjectService projects;
    @Autowired Json json;
    @Test @Timeout(45) void actualProviderTimeoutPreservesItsCauseAndUncertainUsage() {
        SECONDS.set(1);
        String id=projects.create("请求超时验证","核对人物信息","保持已知信息",1,BigDecimal.TEN,BigDecimal.ONE,"local","gpt-6.1-sol","low");
        store.update("tasks.cancelNovel",id);
        String taskId=store.enqueue(id,"MEMORY",1,0,0,UUID.randomUUID().toString(),Map.of());
        Throwable failure=catchThrowable(()->model.call(store.one("tasks.find",taskId),"只输出合法 JSON。",
                "逐项分析100组人物、物品及知情范围，并输出全部100组 JSON 记录。每组包含人物、物品、获知时间和核验理由。"));
        assertThat(failure).isNotNull();
        var causes=new ArrayList<Throwable>();
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) causes.add(cause);
        assertThat(causes).anyMatch(HttpTimeoutException.class::isInstance);
        assertThat(failure.getMessage()).doesNotContain("未取得最终文本响应");
        var call=db.one("SELECT * FROM model_calls WHERE task_id=?",taskId);
        assertThat(call.get("status")).isEqualTo("UNCERTAIN");
        assertThat(call.get("actual")).isNull();
        assertThat(Store.string(call,"error")).contains("timed out");
        assertThat(json.read(Store.string(call,"usage_json")).path("pendingRequests").asInt()).isEqualTo(1);
        assertThat(db.list("SELECT status FROM model_call_turns WHERE task_id=?",taskId)).allSatisfy(row->assertThat(row.get("status")).isEqualTo("UNCERTAIN"));
    }
    @Test @Timeout(90) void finalResponsePreservesTheActualProviderOutcomeAndUsage() {
        SECONDS.set(30);
        String id=projects.create("最终响应超时验证","核对人物信息","保持已知信息",1,BigDecimal.TEN,BigDecimal.ONE,"local","gpt-6.1-sol","low");
        store.update("tasks.cancelNovel",id);
        String taskId=store.enqueue(id,"MEMORY",1,0,0,UUID.randomUUID().toString(),Map.of());
        var output=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        Throwable failure=catchThrowable(()->output.set(model.call(store.one("tasks.find",taskId),"使用授权工具，最终仅输出合法 JSON。",
                "本次必须首先调用一次 read_memory，path=current_state.md，offset=0，length=6000。工具返回之前不要生成最终答案。"+
                "取得状态后，输出全部200个人物的 JSON 数组，每个人物必须包含至少100个中文字符的信息边界分析，逐项完整列出，禁止合并或省略条目。")));
        var turns=db.list("SELECT * FROM model_call_turns WHERE task_id=? ORDER BY turn_no",taskId);
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).get("status")).isEqualTo("COMPLETE");
        var call=db.one("SELECT * FROM model_calls WHERE task_id=?",taskId);
        if(failure==null) {
            assertThat(call.get("status")).isEqualTo("COMPLETE");
            assertThat(turns.get(1).get("status")).isEqualTo("COMPLETE");
            var response=json.read(Store.string(turns.get(1),"response_body")).path("choices").path(0);
            assertThat(response.path("finish_reason").asText()).isEqualTo("stop");
            assertThat(output.get()).isEqualTo(json.read(response.path("message").path("content").asText()));
            return;
        }
        assertThat(failure.getMessage()).contains("模型请求失败","30秒").doesNotContain("未取得最终文本响应");
        var causes=new ArrayList<Throwable>();for(Throwable cause=failure;cause!=null;cause=cause.getCause()) causes.add(cause);
        assertThat(causes).anyMatch(HttpTimeoutException.class::isInstance);
        assertThat(turns.get(1).get("status")).isEqualTo("UNCERTAIN");
        assertThat(call.get("status")).isEqualTo("UNCERTAIN");assertThat(call.get("actual")).isNull();
        var usage=json.read(Store.string(call,"usage_json"));
        assertThat(usage.path("known_prompt_tokens").asLong()).isPositive();
        assertThat(usage.path("completedRequests").asInt()).isEqualTo(1);
        assertThat(call.get("response")).isNull();
    }
}
