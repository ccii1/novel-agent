package dev.novel;

import dev.novel.integration.model.ModelRateLimiter;
import dev.novel.service.BudgetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_REDIS_TEST",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:redis;DB_CLOSE_DELAY=-1",
        "novel.engine-enabled=false","novel.redis.enabled=true","novel.model.requests-per-minute=4"})
class RedisIntegrationTest {
    private static final String PREFIX="novel-agent:test:"+UUID.randomUUID();
    @DynamicPropertySource static void prefix(DynamicPropertyRegistry registry) { registry.add("novel.redis.key-prefix",()->PREFIX); }
    @Autowired ModelRateLimiter limiter;
    @Autowired RedissonClient client;

    @Test void twoClientsShareConcurrentQuotaAndReconnectKeepsConsumedPermits() throws Exception {
        Config config=new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("NOVEL_REDIS_ADDRESS","redis://127.0.0.1:6379"))
                .setPassword(System.getenv("NOVEL_REDIS_PASSWORD")).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        var second=Redisson.create(config);
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            var shared=second.getRateLimiter(PREFIX+":model:requests:v2");
            assertThat(shared.trySetRate(RateType.OVERALL,4,Duration.ofMinutes(1))).isFalse();
            List<Future<Boolean>> results=new ArrayList<>();
            for(int index=0;index<20;index++) {
                boolean primary=index%2==0;
                results.add(pool.submit(() -> {
                    if(!primary) return shared.tryAcquire();
                    try { limiter.acquire();return true; }
                    catch(BudgetService.RateLimited limited) { return false; }
                }));
            }
            int accepted=0;
            for(var result:results) if(result.get()) accepted++;
            assertThat(accepted).isEqualTo(4);
            second.shutdown();
            var reconnected=Redisson.create(config);
            try { assertThat(reconnected.getRateLimiter(PREFIX+":model:requests:v2").tryAcquire()).isFalse(); }
            finally { reconnected.shutdown(); }
        } finally {
            pool.shutdownNow();
            if(!second.isShutdown()) second.shutdown();
            client.getRateLimiter(PREFIX+":model:requests:v2").delete();
        }
    }
}
