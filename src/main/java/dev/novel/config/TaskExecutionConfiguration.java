package dev.novel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TaskExecutionConfiguration {
    @Bean public ThreadPoolTaskExecutor novelTaskExecutor(@Value("${novel.workers}") int workers,
            @Value("${novel.model.timeout-seconds}") int timeout) {
        if(workers<1 || workers>32) throw new IllegalArgumentException("workers 应为1至32");
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(workers);
        executor.setThreadNamePrefix("novel-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(timeout+10);
        return executor;
    }
}
