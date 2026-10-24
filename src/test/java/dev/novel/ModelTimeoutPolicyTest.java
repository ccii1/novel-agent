package dev.novel;

import dev.novel.integration.model.ModelTimeoutPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ModelTimeoutPolicyTest {
    @Test void roleSpecificTimeoutAndOuterDeadlinesUseTheActualAllowedRequestCount() {
        var environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("timeout-test",Map.of(
                "novel.model.timeout-seconds",180,"novel.model.role-timeout-seconds.memory",600)));
        var policy=new ModelTimeoutPolicy(environment);
        var memory=policy.limits("MEMORY",4);
        assertThat(memory.request()).isEqualTo(Duration.ofSeconds(600));
        assertThat(memory.framework()).isEqualTo(Duration.ofSeconds(615));
        assertThat(memory.task()).isEqualTo(Duration.ofSeconds(2465));
        assertThat(policy.limits("WRITE",4).request()).isEqualTo(Duration.ofSeconds(180));
    }
    @Test void invalidTimeoutConfigurationFailsBeforeSendingRequests() {
        var environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("timeout-test",Map.of("novel.model.timeout-seconds",0)));
        var policy=new ModelTimeoutPolicy(environment);
        assertThatThrownBy(()->policy.limits("MEMORY",4)).isInstanceOf(IllegalArgumentException.class);
    }
}
