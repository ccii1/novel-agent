package dev.novel.integration.model;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.service.BudgetService;
import dev.novel.service.SkillService;
import dev.novel.support.Json;
import io.agentscope.core.model.transport.HttpResponse;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class AgentCallAccounting {
    private final Map<String,Object> task;
    private final ModelCatalog.Selection selected;
    private final SkillService.Snapshot skill;
    private final Store store;
    private final ModelCallMapper calls;
    private final BudgetService budget;
    private final ModelRateLimiter limiter;
    private final Json json;
    private final int maxContext,maxOutput,maxRequests;
    private final String callId=UUID.randomUUID().toString();
    private String turnId;
    private int turns;
    private long started,turnStarted,input,output,cached,latency;
    private boolean allCached=true,lastStopped;
    private boolean answered;
    private boolean requestOpen;
    private String lastContent;
    private BigDecimal reserved;
    private final ModelTimeoutPolicy.Limits timeouts;
    AgentCallAccounting(Map<String,Object> task,ModelCatalog.Selection selected,SkillService.Snapshot skill,Store store,
            ModelCallMapper calls,BudgetService budget,ModelRateLimiter limiter,Json json,int maxContext,int maxOutput,int maxRequests,ModelTimeoutPolicy.Limits timeouts) {
        this.task=task;this.selected=selected;this.skill=skill;this.store=store;this.calls=calls;this.budget=budget;
        this.limiter=limiter;this.json=json;this.maxContext=maxContext;this.maxOutput=maxOutput;this.maxRequests=maxRequests;
        this.timeouts=timeouts;
    }
    void before(String body) {
        if(turns>=maxRequests) throw new IllegalStateException("Agent 已达到模型请求次数上限："+maxRequests);
        int requestBytes=body.getBytes(StandardCharsets.UTF_8).length;
        if(requestBytes>maxContext) throw new IllegalStateException("第"+(turns+1)+"次模型请求需要"+requestBytes+"字节，超过"+maxContext+
                "字节上限；该次请求尚未发送，前"+turns+"次调用的用量继续保留");
        try { limiter.acquire(); }
        catch(BudgetService.RateLimited limited) {
            if(started==0) throw limited;
            throw new IllegalStateException("Agent 后续请求达到调用频率限制，已有用量已保存，请稍后审核并重新执行");
        }
        if(started==0) {
            // 按允许的请求次数预留最坏费用，后续各轮真实用量统一结算。
            reserved=cost((maxContext+1024L)*maxRequests,(long)maxOutput*maxRequests);
            budget.reserve(task,reserved,selected.model(),selected.inputPrice(),selected.outputPrice(),skill,body,
                    selected.provider(),selected.billingMode(),AgentScopeExecutor.FRAMEWORK,callId);
            started=System.nanoTime();
            event("MODEL_REQUEST_SENT",Map.of("provider",selected.provider(),"model",selected.model(),"reasoningEffort",selected.reasoningEffort(),"reserved",reserved,"billingMode",selected.billingMode(),"callId",callId,"maxRequests",maxRequests,"contextBytes",requestBytes));
        }
        turns++;turnId=UUID.randomUUID().toString();turnStarted=System.nanoTime();
        try { budget.recordTurn(turnId,callId,Store.string(task,"id"),turns,body); }
        catch(BudgetService.RateLimited limited) { throw new IllegalStateException("Agent 后续请求达到数据库调用频率限制，已有用量已保存，请稍后重新执行"); }
        answered=false;requestOpen=true;
        event("MODEL_TURN_STARTED",Map.of("turn",turns,"limit",maxRequests,"contextBytes",requestBytes,"contextLimitBytes",maxContext,
                "requestTimeoutSeconds",timeouts.request().toSeconds(),"taskTimeoutSeconds",timeouts.task().toSeconds()));
    }
    void after(HttpResponse response) {
        long elapsed=Math.max(0,(System.nanoTime()-turnStarted)/1_000_000);latency+=elapsed;
        event("MODEL_RESPONSE_RECEIVED",Map.of("callId",callId,"latencyMs",elapsed,"httpStatus",response.getStatusCode(),"turn",turns));
        if(!response.isSuccessful()) {
            store.update("modelTurns.finish","UNCERTAIN",response.getBody(),null,null,null,null,elapsed,turnId);
            throw new IllegalStateException("模型 API 返回 HTTP "+response.getStatusCode()+"，请核对供应商费用");
        }
        var root=json.read(response.getBody());var usage=root.path("usage");
        if(!usage.path("prompt_tokens").canConvertToLong() || !usage.path("completion_tokens").canConvertToLong()) throw new IllegalStateException("模型响应缺少 token 计费信息");
        long in=usage.path("prompt_tokens").asLong(),out=usage.path("completion_tokens").asLong();
        if(in<0 || out<0) throw new IllegalStateException("模型 token 用量无效");
        var cache=usage.path("prompt_tokens_details").path("cached_tokens");
        if(cache.isMissingNode()) cache=usage.path("prompt_cache_hit_tokens");
        Long hit=cache.isIntegralNumber() && cache.canConvertToLong() && cache.asLong()>=0?cache.asLong():null;
        input+=in;output+=out;if(hit==null) allCached=false;else cached+=hit;
        answered=true;requestOpen=false;lastContent=root.path("choices").path(0).path("message").path("content").asText();
        String finish=root.path("choices").path(0).path("finish_reason").asText();
        lastStopped=finish.equals("stop");
        boolean valid=lastStopped || finish.equals("tool_calls");
        store.update("modelTurns.finish",valid?"COMPLETE":"INVALID",response.getBody(),in,out,hit,cost(in,out),elapsed,turnId);
        if(!valid) throw new IllegalStateException("模型输出未正常结束，请调整输出额度后重新执行");
    }
    void complete(String result) {
        if(!lastStopped) throw new IllegalStateException("Agent 未取得最终文本响应");
        BigDecimal actual=cost(input,output);
        calls.observation(callId,latency,json.write(Map.of("prompt_tokens",input,"completion_tokens",output,"requests",turns)),allCached?cached:null);
        if(calls.complete("COMPLETE",actual,input,output,result,callId)!=1) throw new IllegalStateException("模型调用结算记录不存在");
        if(actual.compareTo(reserved)>0) throw new IllegalStateException("实际费用超过预留金额，请核对供应商单价");
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("callId",callId);metadata.put("inputTokens",input);metadata.put("outputTokens",output);metadata.put("cachedInputTokens",allCached?cached:null);
        metadata.put("estimatedCost",actual);metadata.put("billingMode",selected.billingMode());metadata.put("requests",turns);
        event("MODEL_USAGE_RECORDED",metadata);
    }
    void failed(Exception failure) {
        if(started==0) return;
        if(answered || !requestOpen) {
            // 已取得明确用量的失败保留实际费用，未知网络调用另外标记为 UNCERTAIN。
            calls.observation(callId,latency,json.write(Map.of("prompt_tokens",input,"completion_tokens",output,"requests",turns)),allCached?cached:null);
            calls.complete("INVALID",cost(input,output),input,output,lastContent,callId);
            return;
        }
        if(turnId!=null) store.update("modelTurns.uncertain",turnId);
        calls.observation(callId,Math.max(0,(System.nanoTime()-started)/1_000_000),json.write(Map.of(
                "known_prompt_tokens",input,"known_completion_tokens",output,"completedRequests",turns-1,"pendingRequests",1)),null);
        calls.transportFailure(callId,Math.max(0,(System.nanoTime()-started)/1_000_000));
        calls.markUncertain(failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),"模型请求未完成"),callId);
    }
    private BigDecimal cost(long in,long out) {
        return selected.inputPrice().multiply(BigDecimal.valueOf(in)).add(selected.outputPrice().multiply(BigDecimal.valueOf(out))).divide(BigDecimal.valueOf(1_000_000),6,RoundingMode.CEILING);
    }
    private void event(String type,Object data) { store.event((String)task.get("novel_id"),Store.string(task,"id"),type,json.write(data),(String)task.get("execution_id")); }
}
