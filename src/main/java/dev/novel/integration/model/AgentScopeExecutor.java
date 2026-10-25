package dev.novel.integration.model;

import dev.novel.repository.Store;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.middleware.ModelCallInput;
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
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AgentScopeExecutor {
    public static final String FRAMEWORK = "agentscope-java-2.0.4";
    private final Store store;
    private final ModelTimeoutPolicy timeouts;
    private final int maxContext;
    private final dev.novel.agent.runtime.AgentRuntimeFactory runtimes;
    private final org.springframework.core.env.Environment environment;
    private final dev.novel.support.Json json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AgentScopeExecutor(Store store,ModelTimeoutPolicy timeouts,dev.novel.agent.runtime.AgentRuntimeFactory runtimes,
            @Value("${novel.max-context-bytes}") int maxContext,org.springframework.core.env.Environment environment,dev.novel.support.Json json) {
        this.store = store;
        this.timeouts=timeouts;
        this.runtimes=runtimes;
        this.maxContext=maxContext;
        this.environment=environment;this.json=json;
    }

    public String execute(Map<String,Object> task, ModelCatalog.Selection selected, String system, String prompt,
            int maxOutput, Consumer<String> beforeRequest, Consumer<HttpResponse> afterResponse) {
        URI endpoint = ModelEndpoint.resolve(selected.endpoint());
        int maxRequests=runtimes.spec(Store.string(task,"kind")).maxRequests();
        var limits=limits(task);
        var modelFailure=new AtomicReference<Throwable>();
        var optionsBuilder = GenerateOptions.builder().maxTokens(maxOutput).stream(false)
                .responseFormat(ResponseFormat.jsonObject())
                .executionConfig(ExecutionConfig.builder().timeout(limits.framework()).maxAttempts(1).build());
        // SDK 将此字段序列化为 Chat Completions 的 reasoning_effort，空值交给服务默认设置。
        if (!selected.reasoningEffort().isEmpty()) optionsBuilder.reasoningEffort(selected.reasoningEffort());
        if(environment.getProperty("novel.model.prompt-cache-key-enabled",Boolean.class,false))
            optionsBuilder.additionalBodyParams(Map.of("prompt_cache_key",dev.novel.service.SkillService.digest(
                    Store.string(task,"user_id")+":"+selected.provider()+":"+selected.model()+":"+system)));
        var options = optionsBuilder.build();
        var delegate = JdkHttpTransport.builder().client(client).config(HttpTransportConfig.builder()
                .connectTimeout(Duration.ofSeconds(20)).responseTimeout(limits.request()).readTimeout(limits.request()).build()).build();
        var transport = new AccountedTransport(delegate, endpoint, beforeRequest, afterResponse,maxRequests);
        var model = OpenAIChatModel.builder().modelName(selected.model()).apiKey(selected.apiKey())
                .baseUrl(endpoint.getScheme()+"://"+endpoint.getRawAuthority()).endpointPath(endpoint.getPath())
                .stream(false).generateOptions(options).httpTransport(transport).build();
        MiddlewareBase audit = new MiddlewareBase() {
            @Override public Flux<AgentEvent> onModelCall(Agent agent,RuntimeContext context,ModelCallInput input,
                    Function<ModelCallInput,Flux<AgentEvent>> next) {
                return next.apply(input).doOnError(failure->modelFailure.compareAndSet(null,failure));
            }
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
        var contextHook=new AgentContextHook(task,store,json,model,environment,selected,maxContext,maxOutput,
                failure->modelFailure.compareAndSet(null,failure));
        try (var handle=runtimes.build(task,model,system,options,audit,contextHook)) {
            transport.contextBudget=body->handle.tools().reserveRequestSpace(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,maxContext);
            io.agentscope.core.message.Msg reply;
            try {
                var runtime=RuntimeContext.builder().userId(Store.string(task,"user_id")).sessionId(Store.string(task,"id")).build();
                reply=handle.agent().call(List.of(new UserMessage(prompt)),runtime).block(limits.task());
            } catch(RuntimeException failure) {
                throw failedRequest(limits,modelFailure.get()==null?failure:modelFailure.get());
            }
            if(modelFailure.get()!=null) throw failedRequest(limits,modelFailure.get());
            handle.tools().check();
            if (reply == null || reply.getTextContent().isBlank()) throw new IllegalStateException("AgentScope 未返回文本");
            runtimes.validateDelegation(task,handle,reply.getTextContent());
            return reply.getTextContent();
        } finally {
            transport.close();
        }
    }

    public ModelTimeoutPolicy.Limits limits(Map<String,Object> task) {
        return timeouts.limits(Store.string(task,"kind"),runtimes.spec(Store.string(task,"kind")).maxRequests());
    }
    private IllegalStateException failedRequest(ModelTimeoutPolicy.Limits limits,Throwable failure) {
        return new IllegalStateException("模型请求失败（单次等待上限"+limits.request().toSeconds()+"秒）："+failure.getMessage(),failure);
    }

    static final class AccountedTransport implements HttpTransport {
        private final HttpTransport delegate;
        private final URI endpoint;
        private final Consumer<String> beforeRequest;
        private final Consumer<HttpResponse> afterResponse;
        private final java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        private final int maxRequests;
        private Consumer<String> contextBudget=body->{};
        private final java.util.concurrent.atomic.AtomicInteger retries=new java.util.concurrent.atomic.AtomicInteger();

        AccountedTransport(HttpTransport delegate, URI endpoint, Consumer<String> beforeRequest, Consumer<HttpResponse> afterResponse,int maxRequests) {
            this.delegate=delegate; this.endpoint=endpoint; this.beforeRequest=beforeRequest; this.afterResponse=afterResponse;
            this.maxRequests=maxRequests;
        }
        @Override public HttpResponse execute(HttpRequest request) {
            var config=io.github.resilience4j.retry.RetryConfig.custom().maxAttempts(3)
                    .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(1000,2))
                    .retryOnException(error->error instanceof RetryableModelRequest && retries.incrementAndGet()<=2).build();
            return io.github.resilience4j.retry.Retry.decorateSupplier(
                    io.github.resilience4j.retry.Retry.of("model-request",config),()->attempt(request)).get();
        }
        private HttpResponse attempt(HttpRequest request) {
            if (sent.incrementAndGet()>maxRequests) throw new IllegalStateException("Agent 模型请求达到次数上限，请审核后重新执行");
            contextBudget.accept(request.getBody());
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
