package dev.novel.integration.model;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Locale;

@Component
public class ModelTimeoutPolicy {
    private final Environment environment;
    public record Limits(Duration request,Duration framework,Duration task) {}
    public ModelTimeoutPolicy(Environment environment) { this.environment=environment; }
    public Limits limits(String kind,int maxRequests) {
        int seconds=environment.getProperty("novel.model.role-timeout-seconds."+kind.toLowerCase(Locale.ROOT),Integer.class,
                environment.getProperty("novel.model.timeout-seconds",Integer.class,180));
        if(seconds<1 || seconds>3600 || maxRequests<1) throw new IllegalArgumentException("模型请求超时需要为1至3600秒，请求次数必须为正数");
        var request=Duration.ofSeconds(seconds);
        // HTTP 先结束等待，随后才到框架和整个任务的截止时间。
        var framework=request.plusSeconds(15);
        return new Limits(request,framework,framework.multipliedBy(maxRequests).plusSeconds(5));
    }
}
