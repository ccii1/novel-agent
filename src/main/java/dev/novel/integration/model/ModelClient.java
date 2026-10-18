package dev.novel.integration.model;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.support.Json;
import dev.novel.service.BudgetService;
import dev.novel.service.SkillService;
import io.agentscope.core.model.transport.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ModelClient {
    private final Json json;
    private final BudgetService budget;
    private final int maxOutput, maxContext;
    private final ModelCatalog catalog;
    private final SkillService skills;
    private final AgentScopeExecutor executor;
    private final ModelRateLimiter rateLimiter;
    private final ModelCallMapper calls;
    private final Store events;
    public ModelClient(Json json, BudgetService budget,
            @Value("${novel.model.max-output-tokens}") int maxOutput, @Value("${novel.max-context-bytes}") int maxContext,
            ModelCatalog catalog,SkillService skills,AgentScopeExecutor executor,ModelRateLimiter rateLimiter,ModelCallMapper calls,Store events) {
        this.json=json; this.budget=budget;
        this.maxOutput=maxOutput; this.maxContext=maxContext;
        this.catalog=catalog;
        this.skills=skills;
        this.executor=executor;
        this.rateLimiter=rateLimiter;
        this.calls=calls;
        this.events=events;
    }
    public boolean configured() { return catalog.resolve(null,"","PLAN").ready(); }
    public void validateCheckpoint(Map<String,Object> task) {
        var skill=skills.load(Store.string(task,"kind"),Store.integer(task,"chapter_no"));
        var recorded=calls.checkpoint(Store.string(task,"id"));
        if(recorded==null || !"COMPLETE".equals(recorded.status()) || !skill.fingerprint().equals(recorded.skillFingerprint()))
            throw new IllegalStateException("任务结果缺少当前技能版本的调用记录，请审核后重新执行");
    }
    public JsonNode call(Map<String,Object> task, String system, String prompt) throws Exception {
        var skill=skills.load(Store.string(task,"kind"),Store.integer(task,"chapter_no"));
        system=system+"\n"+skill.instruction();
        var previous = calls.checkpoint(Store.string(task,"id"));
        if (previous!=null) {
            if ("COMPLETE".equals(previous.status())) {
                if(!skill.fingerprint().equals(previous.skillFingerprint()))
                    throw new IllegalStateException("已保存响应的技能指纹与当前规则不同，请审核后重新执行");
                if(previous.actual().compareTo(previous.reserved())>0)
                    throw new IllegalStateException("已保存调用超过预留费用，请核对后重新执行");
                return json.read(previous.response());
            }
            throw new IllegalStateException("调用已记录且未取得可复用响应，请核对费用并明确重新执行");
        }
        var selected=catalog.forTask(task);
        if(!selected.ready()) throw new IllegalStateException("请配置模型服务、模型名称和计费方式");
        var selectedInputPrice=selected.inputPrice();
        var selectedOutputPrice=selected.outputPrice();
        String callId=UUID.randomUUID().toString();
        var reserve = new AtomicReference<BigDecimal>();
        var started=new AtomicLong();
        try {
            String content = executor.execute(task,selected,system,prompt,maxOutput,body -> {
                int bytes=json.write(json.read(body).path("messages")).getBytes(StandardCharsets.UTF_8).length;
                if(bytes>maxContext) throw new IllegalStateException("上下文超过字节限制，请调整设定或阶段大纲");
                rateLimiter.acquire();
                reserve.set(cost(bytes+1024L,maxOutput,selectedInputPrice,selectedOutputPrice));
                budget.reserve(task,reserve.get(),selected.model(),selectedInputPrice,selectedOutputPrice,skill,body,
                        selected.provider(),selected.billingMode(),AgentScopeExecutor.FRAMEWORK,callId);
                started.set(System.nanoTime());
                storeEvent(task,"MODEL_REQUEST_SENT",Map.of("provider",selected.provider(),"model",selected.model(),
                        "contextBytes",bytes,"maxOutputTokens",maxOutput,"reserved",reserve.get(),"callId",callId));
            },response -> {
                long latency=elapsed(started.get());
                storeEvent(task,"MODEL_RESPONSE_RECEIVED",Map.of("callId",callId,"httpStatus",response.getStatusCode(),"latencyMs",latency));
                if(!response.isSuccessful()) {
                    calls.observation(callId,latency,null,null);
                    throw new IllegalStateException("模型 API 返回 HTTP "+response.getStatusCode()+"，请在供应商后台核对是否计费");
                }
                var root=json.read(response.getBody());
                var usage=root.path("usage");
                var cached=usage.path("prompt_tokens_details").path("cached_tokens");
                if(cached.isMissingNode()) cached=usage.path("prompt_cache_hit_tokens");
                Long cachedTokens=cached.isIntegralNumber() && cached.canConvertToLong() && cached.asLong()>=0?cached.asLong():null;
                calls.observation(callId,latency,usage.isMissingNode()?null:json.write(usage),cachedTokens);
                settle(callId,selected,reserve.get(),response);
                Map<String,Object> usageMetadata=new LinkedHashMap<>();
                usageMetadata.put("callId",callId);usageMetadata.put("inputTokens",usage.path("prompt_tokens").asLong());
                usageMetadata.put("outputTokens",usage.path("completion_tokens").asLong());usageMetadata.put("cachedInputTokens",cachedTokens);
                storeEvent(task,"MODEL_USAGE_RECORDED",usageMetadata);
            });
            return json.read(content);
        } catch (Exception e) {
            if(started.get()!=0) {
                // HTTP 未返回时仍保留本地等待时间；已有 usage 不覆盖。
                calls.transportFailure(callId,elapsed(started.get()));
            }
            calls.markUncertain(e.getClass().getSimpleName(),callId);
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
                if(cause instanceof BudgetService.RateLimited limited) throw limited;
                if(cause instanceof BudgetService.BudgetExceeded exceeded) throw exceeded;
            }
            throw e;
        }
    }
    private long elapsed(long started) { return Math.max(0,(System.nanoTime()-started)/1_000_000); }
    private void storeEvent(Map<String,Object> task,String type,Object metadata) {
        events.event((String)task.get("novel_id"),Store.string(task,"id"),type,json.write(metadata),(String)task.get("execution_id"));
    }
    private void settle(String callId,ModelCatalog.Selection selected,BigDecimal reserve,HttpResponse response) {
        if(!response.isSuccessful())
            throw new IllegalStateException("模型 API 返回 HTTP " + response.getStatusCode() + "，请在供应商后台核对是否计费");
        var root=json.read(response.getBody());
        var usage=root.path("usage");
        if(!usage.path("prompt_tokens").canConvertToLong() || !usage.path("completion_tokens").canConvertToLong())
            throw new IllegalStateException("模型响应缺少 token 计费信息");
        long input=usage.get("prompt_tokens").asLong(),output=usage.get("completion_tokens").asLong();
        if(input<0 || output<0) throw new IllegalStateException("模型 token 用量无效");
        var actual=cost(input,output,selected.inputPrice(),selected.outputPrice());
        String content=root.path("choices").path(0).path("message").path("content").asText();
        boolean stopped="stop".equals(root.path("choices").path(0).path("finish_reason").asText());
        if(calls.complete(stopped?"COMPLETE":"INVALID",actual,input,output,content,callId)!=1)
            throw new IllegalStateException("本次模型调用的结算记录不存在");
        if(actual.compareTo(reserve)>0) throw new IllegalStateException("实际计费超过预留金额，请核查供应商单价和 token 规则");
        if(!stopped) throw new IllegalStateException("模型输出未正常结束，请调整输出额度后重新执行");
    }
    private BigDecimal cost(long input, long output,BigDecimal inputPrice,BigDecimal outputPrice) {
        return inputPrice.multiply(BigDecimal.valueOf(input)).add(outputPrice.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000),6,RoundingMode.CEILING);
    }
}
