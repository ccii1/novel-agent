package dev.novel.integration.model;

import dev.novel.repository.Store;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
public class AgentScopeExecutor {
    public static final String FRAMEWORK = "agentscope-java-2.0.4";
    private final Store store;
    private final Duration timeout;
    private final dev.novel.agent.runtime.AgentRuntimeFactory runtimes;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AgentScopeExecutor(Store store, @Value("${novel.model.timeout-seconds}") int timeout,dev.novel.agent.runtime.AgentRuntimeFactory runtimes) {
        this.store = store;
        this.timeout = Duration.ofSeconds(timeout);
        this.runtimes=runtimes;
    }

    public String execute(Map<String,Object> task, ModelCatalog.Selection selected, String system, String prompt,
            int maxOutput, Consumer<String> beforeRequest, Consumer<HttpResponse> afterResponse) {
        URI endpoint = ModelEndpoint.resolve(selected.endpoint());
        var optionsBuilder = GenerateOptions.builder().maxTokens(maxOutput).stream(false)
                .responseFormat(ResponseFormat.jsonObject())
                .executionConfig(ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build());
        // SDK 将此字段序列化为 Chat Completions 的 reasoning_effort，空值交给服务默认设置。
        if (!selected.reasoningEffort().isEmpty()) optionsBuilder.reasoningEffort(selected.reasoningEffort());
        var options = optionsBuilder.build();
        var delegate = JdkHttpTransport.builder().client(client).config(HttpTransportConfig.builder()
                .connectTimeout(Duration.ofSeconds(20)).responseTimeout(timeout).readTimeout(timeout).build()).build();
        var transport = new AccountedTransport(delegate, endpoint, beforeRequest, afterResponse,runtimes.spec(Store.string(task,"kind")).maxRequests());
        var model = OpenAIChatModel.builder().modelName(selected.model()).apiKey(selected.apiKey())
                .baseUrl(endpoint.getScheme()+"://"+endpoint.getRawAuthority()).endpointPath(endpoint.getPath())
                .stream(false).generateOptions(options).httpTransport(transport).build();
        MiddlewareBase audit = new MiddlewareBase() {
            @Override public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                    Function<ReasoningInput,Flux<AgentEvent>> next) {
                return Flux.defer(() -> {
                    store.event((String)task.get("novel_id"),(String)task.get("id"),"AGENTSCOPE_REASONING_STARTED",Store.string(task,"kind"),(String)task.get("execution_id"));
                    return next.apply(input).doOnComplete(() -> store.event((String)task.get("novel_id"),
                            (String)task.get("id"),"AGENTSCOPE_REASONING_FINISHED",Store.string(task,"kind"),(String)task.get("execution_id")));
                });
            }
        };
        // 每个任务独立创建 Agent，长期故事记忆由业务模块按版本提供。
        try (var handle=runtimes.build(task,model,system,options,audit)) {
            var reply = handle.agent().call(List.of(new UserMessage(prompt))).block(timeout.multipliedBy(runtimes.spec(Store.string(task,"kind")).maxRequests()).plusSeconds(5));
            handle.tools().check();
            if (reply == null || reply.getTextContent().isBlank()) throw new IllegalStateException("AgentScope 未返回文本");
            runtimes.validateDelegation(task,handle,reply.getTextContent());
            return reply.getTextContent();
        } finally {
            transport.close();
        }
    }

    static final class AccountedTransport implements HttpTransport {
        private final HttpTransport delegate;
        private final URI endpoint;
        private final Consumer<String> beforeRequest;
        private final Consumer<HttpResponse> afterResponse;
        private final java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        private final int maxRequests;

        AccountedTransport(HttpTransport delegate, URI endpoint, Consumer<String> beforeRequest, Consumer<HttpResponse> afterResponse,int maxRequests) {
            this.delegate=delegate; this.endpoint=endpoint; this.beforeRequest=beforeRequest; this.afterResponse=afterResponse;
            this.maxRequests=maxRequests;
        }
        @Override public HttpResponse execute(HttpRequest request) {
            if (sent.incrementAndGet()>maxRequests) throw new IllegalStateException("Agent 模型请求达到次数上限，请审核后重新执行");
            beforeRequest.accept(request.getBody());
            // 使用已校验的完整地址，保留供应商配置中的查询参数。
            var actual = HttpRequest.builder().url(endpoint.toString()).method(request.getMethod())
                    .headers(request.getHeaders()).body(request.getBody()).build();
            var response = delegate.execute(actual);
            afterResponse.accept(response);
            return response;
        }
        @Override public Flux<String> stream(HttpRequest request) {
            return Flux.error(new IllegalStateException("当前任务要求非流式模型响应"));
        }
        @Override public void close() { delegate.close(); }
    }
}
