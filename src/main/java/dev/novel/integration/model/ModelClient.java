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

@Service
public class ModelClient {
    private final Store store;
    private final Json json;
    private final BudgetService budget;
    private final int maxOutput, maxContext;
    private final ModelCatalog catalog;
    private final SkillService skills;
    private final AgentScopeExecutor executor;
    private final ModelRateLimiter rateLimiter;
    private final ModelCallMapper calls;
    public ModelClient(Store store, Json json, BudgetService budget,
            @Value("${novel.model.max-output-tokens}") int maxOutput, @Value("${novel.max-context-bytes}") int maxContext,
            ModelCatalog catalog,SkillService skills,AgentScopeExecutor executor,ModelRateLimiter rateLimiter,ModelCallMapper calls) {
        this.store=store; this.json=json; this.budget=budget;
        this.maxOutput=maxOutput; this.maxContext=maxContext;
        this.catalog=catalog;
        this.skills=skills;
        this.executor=executor;
        this.rateLimiter=rateLimiter;
        this.calls=calls;
    }
    public boolean configured() { return catalog.resolve(null,"","PLAN").ready(); }
    public void validateCheckpoint(Map<String,Object> task) {
        var skill=skills.load(Store.string(task,"kind"),Store.integer(task,"chapter_no"));
        var recorded=store.list("calls.byTask",task.get("id"));
        if(recorded.isEmpty() || !"COMPLETE".equals(recorded.get(0).get("status")) || !skill.fingerprint().equals(recorded.get(0).get("skill_fingerprint")))
            throw new IllegalStateException("任务结果缺少当前技能版本的调用记录，请审核后重新执行");
    }
    public JsonNode call(Map<String,Object> task, String system, String prompt) throws Exception {
        var skill=skills.load(Store.string(task,"kind"),Store.integer(task,"chapter_no"));
        system=system+"\n"+skill.instruction();
        var prior = store.list("calls.byTask", task.get("id"));
        if (!prior.isEmpty()) {
            var previous = prior.get(0);
            if ("COMPLETE".equals(previous.get("status"))) {
                if(!skill.fingerprint().equals(previous.get("skill_fingerprint")))
                    throw new IllegalStateException("已保存响应的技能指纹与当前规则不同，请审核后重新执行");
                if(((BigDecimal)previous.get("actual")).compareTo((BigDecimal)previous.get("reserved"))>0)
                    throw new IllegalStateException("已保存调用超过预留费用，请核对后重新执行");
                return json.read((String)previous.get("response"));
            }
            throw new IllegalStateException("调用已记录且未取得可复用响应，请核对费用并明确重新执行");
        }
        var selected=catalog.forTask(task);
        if(!selected.ready()) throw new IllegalStateException("请配置模型服务、模型名称和计费方式");
        var selectedInputPrice=selected.inputPrice();
        var selectedOutputPrice=selected.outputPrice();
        var reserve = new AtomicReference<BigDecimal>();
        try {
            String content = executor.execute(task,selected,system,prompt,maxOutput,body -> {
                int bytes=json.write(json.read(body).path("messages")).getBytes(StandardCharsets.UTF_8).length;
                if(bytes>maxContext) throw new IllegalStateException("上下文超过字节限制，请调整设定或阶段大纲");
                rateLimiter.acquire();
                reserve.set(cost(bytes+1024L,maxOutput,selectedInputPrice,selectedOutputPrice));
                store.transaction(() -> {
                    budget.reserve(task,reserve.get(),selected.model(),selectedInputPrice,selectedOutputPrice,skill,body,selected.provider(),selected.billingMode());
                    calls.markFramework(AgentScopeExecutor.FRAMEWORK,(String)task.get("id"));
                    return null;
                });
            },response -> settle(task,selected,reserve.get(),response));
            return json.read(content);
        } catch (Exception e) {
            calls.markUncertain(e.getClass().getSimpleName(),(String)task.get("id"));
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
                if(cause instanceof BudgetService.RateLimited limited) throw limited;
                if(cause instanceof BudgetService.BudgetExceeded exceeded) throw exceeded;
            }
            throw e;
        }
    }
    private void settle(Map<String,Object> task,ModelCatalog.Selection selected,BigDecimal reserve,HttpResponse response) {
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
        calls.complete(stopped?"COMPLETE":"INVALID",actual,input,output,content,(String)task.get("id"));
        if(actual.compareTo(reserve)>0) throw new IllegalStateException("实际计费超过预留金额，请核查供应商单价和 token 规则");
        if(!stopped) throw new IllegalStateException("模型输出未正常结束，请调整输出额度后重新执行");
    }
    private BigDecimal cost(long input, long output,BigDecimal inputPrice,BigDecimal outputPrice) {
        return inputPrice.multiply(BigDecimal.valueOf(input)).add(outputPrice.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000),6,RoundingMode.CEILING);
    }
}
