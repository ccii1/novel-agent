package dev.novel.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="novel.redis.enabled",havingValue="true")
public class RedisConfiguration {
    @Bean(destroyMethod="shutdown") RedissonClient redissonClient(
            @Value("${novel.redis.address}") String address,@Value("${novel.redis.password:}") String password) {
        var config=new Config();
        config.setThreads(2).setNettyThreads(4);
        var server=config.useSingleServer().setAddress(address).setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8).setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
                .setConnectTimeout(3000).setTimeout(3000).setRetryAttempts(0);
        if(!password.isBlank()) server.setPassword(password);
        return Redisson.create(config);
    }
}
