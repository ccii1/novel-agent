package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class ModelClient {
    private final Store store;
    private final Json json;
    private final BudgetService budget;
    private final String endpoint, key, model;
    private final BigDecimal inputPrice, outputPrice;
    private final int maxOutput, maxContext, timeout;
    private final Environment environment;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    public ModelClient(Store store, Json json, BudgetService budget,
            @Value("${novel.model.endpoint}") String endpoint, @Value("${novel.model.api-key}") String key,
            @Value("${novel.model.name}") String model, @Value("${novel.model.input-per-million}") BigDecimal inputPrice,
            @Value("${novel.model.output-per-million}") BigDecimal outputPrice,
            @Value("${novel.model.max-output-tokens}") int maxOutput, @Value("${novel.max-context-bytes}") int maxContext,
            @Value("${novel.model.timeout-seconds}") int timeout,Environment environment) {
        this.store=store; this.json=json; this.budget=budget; this.endpoint=endpoint; this.key=key; this.model=model;
        this.inputPrice=inputPrice; this.outputPrice=outputPrice; this.maxOutput=maxOutput; this.maxContext=maxContext; this.timeout=timeout;
        this.environment=environment;
    }
    public boolean configured() { return !endpoint.isBlank() && !key.isBlank() && !model.isBlank() && inputPrice.signum()>0 && outputPrice.signum()>0; }
    public JsonNode call(Map<String,Object> task, String system, String prompt) throws Exception {
        var prior = store.list("SELECT * FROM model_calls WHERE task_id=?", task.get("id"));
        if (!prior.isEmpty()) {
            var previous = prior.get(0);
            if ("COMPLETE".equals(previous.get("status"))) return json.read((String)previous.get("response"));
            throw new IllegalStateException("调用已记录且未取得可复用响应，请核对费用并明确重新执行");
        }
        String prefix="novel.agents."+Store.string(task,"kind").toLowerCase(Locale.ROOT).replace('_','-')+".";
        String selectedEndpoint=environment.getProperty(prefix+"endpoint",endpoint);
        String selectedKey=environment.getProperty(prefix+"api-key",key);
        String selectedModel=environment.getProperty(prefix+"name",model);
        BigDecimal selectedInputPrice=environment.getProperty(prefix+"input-per-million",BigDecimal.class,inputPrice);
        BigDecimal selectedOutputPrice=environment.getProperty(prefix+"output-per-million",BigDecimal.class,outputPrice);
        if(selectedEndpoint.isBlank() || selectedKey.isBlank() || selectedModel.isBlank() || selectedInputPrice.signum()<=0 || selectedOutputPrice.signum()<=0)
            throw new IllegalStateException("请配置模型 endpoint、API key、名称和人民币 token 单价");
        URI uri = URI.create(selectedEndpoint);
        if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("云端模型地址必须使用 HTTPS");
        var messages = List.of(Map.of("role","system","content",system), Map.of("role","user","content",prompt));
        int bytes = json.write(messages).getBytes(StandardCharsets.UTF_8).length;
        if (bytes>maxContext) throw new IllegalStateException("上下文超过字节限制，请调整设定或阶段大纲");
        var reserve = cost(bytes+1024L, maxOutput,selectedInputPrice,selectedOutputPrice);
        budget.reserve(task, reserve, selectedModel, selectedInputPrice, selectedOutputPrice);
        try {
            String body = json.write(Map.of("model",selectedModel,"messages",messages,"max_tokens",maxOutput,
                    "response_format",Map.of("type","json_object")));
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
                    .header("Authorization","Bearer " + selectedKey).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            var response = http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode()<200 || response.statusCode()>=300)
                throw new IllegalStateException("模型 API 返回 HTTP " + response.statusCode() + "，请在供应商后台核对是否计费");
            var root = json.read(response.body());
            var usage = root.path("usage");
            if (!usage.path("prompt_tokens").canConvertToLong() || !usage.path("completion_tokens").canConvertToLong())
                throw new IllegalStateException("模型响应缺少 token 计费信息");
            long input = usage.get("prompt_tokens").asLong(), output = usage.get("completion_tokens").asLong();
            if (input<0 || output<0) throw new IllegalStateException("模型 token 用量无效");
            var actual = cost(input,output,selectedInputPrice,selectedOutputPrice);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            boolean stopped="stop".equals(root.path("choices").path(0).path("finish_reason").asText());
            store.db.update("UPDATE model_calls SET status=?,actual=?,input_tokens=?,output_tokens=?,response=? WHERE task_id=?",
                    stopped?"COMPLETE":"INVALID",actual,input,output,content,task.get("id"));
            if (actual.compareTo(reserve)>0) throw new IllegalStateException("实际计费超过预留金额，请核查供应商单价和 token 规则");
            if (!stopped)
                throw new IllegalStateException("模型输出未正常结束，请调整输出额度后重新执行");
            return json.read(content);
        } catch (Exception e) {
            store.db.update("UPDATE model_calls SET status='UNCERTAIN',error=? WHERE task_id=? AND status='IN_FLIGHT'", e.getClass().getSimpleName(), task.get("id"));
            throw e;
        }
    }
    private BigDecimal cost(long input, long output,BigDecimal inputPrice,BigDecimal outputPrice) {
        return inputPrice.multiply(BigDecimal.valueOf(input)).add(outputPrice.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000),6,RoundingMode.CEILING);
    }
}
