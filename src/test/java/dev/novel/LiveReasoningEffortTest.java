package dev.novel;

import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

// 显式启用后才调用真实本地服务，原始请求、响应和用量保存在独立文件数据库。
@EnabledIfEnvironmentVariable(named="NOVEL_REASONING_LIVE_TEST", matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:file:./.work/reasoning-live;DB_CLOSE_ON_EXIT=FALSE",
        "novel.engine-enabled=false", "novel.redis.enabled=false", "novel.model.max-output-tokens=512"})
class LiveReasoningEffortTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ModelClient model;
    @Autowired Json json;

    @Test @Timeout(180)
    void sdkSendsSelectedEffortAndPersistsActualRequestsAndUsage() throws Exception {
        for (String effort : new String[]{"low", "high"}) {
            String id=store.enqueue(null,"RESEARCH",0,0,0,"reasoning-probe:"+UUID.randomUUID(),Map.of());
            store.update("tasks.modelChoice","local","gpt-5.6-terra",id);
            store.update("tasks.reasoning",effort,id);
            var result=model.call(store.one("tasks.find",id),
                    "You are verifying an API transport. Return only a JSON object with the integer field answer.",
                    "Compute 17 * 23. Return {\"answer\":391}. Do not write fiction or research ideas.");
            assertThat(result.path("answer").asInt()).isEqualTo(391);
            var call=db.one("SELECT * FROM model_calls WHERE task_id=?",id);
            assertThat(call.get("status")).isEqualTo("COMPLETE");
            assertThat(json.read(Store.string(call,"request_body")).path("reasoning_effort").asText()).isEqualTo(effort);
            assertThat(Store.number(call,"input_tokens")).isPositive();
            assertThat(Store.number(call,"output_tokens")).isPositive();
            assertThat(call.get("actual").toString()).isEqualTo("0.000000");
            var turn=db.one("SELECT request_body,response_body FROM model_call_turns WHERE task_id=?",id);
            assertThat(json.read(Store.string(turn,"request_body")).path("reasoning_effort").asText()).isEqualTo(effort);
            assertThat(json.read(Store.string(turn,"response_body")).path("usage").path("completion_tokens").asLong()).isPositive();
        }
    }
}
