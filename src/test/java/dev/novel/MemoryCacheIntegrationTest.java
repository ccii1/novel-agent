package dev.novel;

import dev.novel.service.MemoryService;
import dev.novel.service.ProjectService;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_REDIS_TEST",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-cache;DB_CLOSE_DELAY=-1",
        "novel.engine-enabled=false","novel.redis.enabled=true"})
class MemoryCacheIntegrationTest {
    private static final String PREFIX="novel-agent:test:memory:"+UUID.randomUUID();
    @DynamicPropertySource static void prefix(DynamicPropertyRegistry registry) { registry.add("novel.redis.key-prefix",()->PREFIX); }
    @Autowired MemoryService memory;
    @Autowired ProjectService projects;
    @Autowired TestDatabase store;
    @Autowired Json json;
    @Autowired RedissonClient redis;

    @Test void cachedRelationshipsKeepNovelVersionAndDepthIndependent() {
        String first=projects.create("密信","调查密信","保持知情范围",1,BigDecimal.TEN,BigDecimal.ONE);
        String second=projects.create("钥匙","调查钥匙","保持知情范围",1,BigDecimal.TEN,BigDecimal.ONE);
        var delta=json.read("""
                {"summary":"交出钥匙","facts":[],"relations":[
                {"subject":"林舟","predicate":"TRUSTS","object":"沈月","detail":"交出钥匙","deleted":false,"evidence":"林舟交出钥匙"},
                {"subject":"沈月","predicate":"KNOWS","object":"密信","detail":"知道内容","deleted":false,"evidence":"沈月知道密信"}]}
                """);
        store.transaction(()-> {memory.commit(first,1,1,delta);return null;});
        try {
            assertThat((List<?>)memory.graph(first,1,List.of("林舟"),1).get("edges")).hasSize(1);
            assertThat((List<?>)memory.graph(first,1,List.of("林舟"),2).get("edges")).hasSize(2);
            assertThat((List<?>)memory.graph(second,1,List.of("林舟"),2).get("edges")).isEmpty();
            var closed=json.read("""
                    {"summary":"收回钥匙","facts":[],"relations":[{"subject":"林舟","predicate":"TRUSTS","object":"沈月",
                    "detail":"关系结束","deleted":true,"evidence":"林舟收回钥匙"}]}
                    """);
            store.transaction(()-> {memory.commit(first,2,2,closed);return null;});
            assertThat((List<?>)memory.graph(first,2,List.of("林舟"),2).get("edges")).isEmpty();
            assertThat((List<?>)memory.graph(first,1,List.of("林舟"),2).get("edges")).hasSize(2);
            assertThat(memory.snapshot(first,1)).hasSize(2);
            assertThat(redis.getKeys().countExists(PREFIX+":memory:v1:"+first+":1")).isEqualTo(1);
        } finally { redis.getKeys().deleteByPattern(PREFIX+":*"); }
    }
}
