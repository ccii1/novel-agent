package dev.novel.integration.model;

import dev.novel.service.BudgetService;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class ModelRateLimiter {
    private final RRateLimiter limiter;
    public ModelRateLimiter(ObjectProvider<RedissonClient> clients,
            @Value("${novel.redis.key-prefix:novel-agent}") String prefix,
            @Value("${novel.model.requests-per-minute:30}") int rate) {
        var client=clients.getIfAvailable();
        if(client==null) { limiter=null; return; }
        limiter=client.getRateLimiter(prefix+":model:requests");
        limiter.trySetRate(RateType.OVERALL,rate,Duration.ofMinutes(1),Duration.ofMinutes(10));
        var settings=limiter.getConfig();
        if(settings.getRate()!=rate || settings.getRateInterval()!=60_000L || settings.getRateType()!=RateType.OVERALL)
            throw new IllegalStateException("Redis 中的模型限流配置与应用不同，请使用独立的 key-prefix 或统一调用额度");
    }
    public void acquire() {
        if(limiter!=null && !limiter.tryAcquire()) throw new BudgetService.RateLimited();
    }
    public String mode() { return limiter==null?"database":"redisson+database"; }
}
