package dev.novel.agent;

import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryCheckAgent extends ReviewAgent {
    public MemoryCheckAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator) { super(context,model,json,validator); }
    public Set<String> kinds() { return Set.of("CHECK_MEMORY"); }
    public String purpose() { return "记忆和关系增量是否得到正文支持，是否错误覆盖历史设定，是否遗漏重要状态变化"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md"); }
    private JsonNode delta(Map<String,Object> task) {
        var payload=json.read(Store.string(task,"payload"));return payload.has("delta")?payload.get("delta"):payload;
    }
    protected String additionalInput(Map<String,Object> task,String body) { return "；待检查记忆="+json.write(delta(task)); }
    protected String additionalSchema() { return "记忆问题可以引用待审核记忆：issues 中填写 quoteSource=MEMORY 与实际存在的 JSON Pointer quotePath；"+
            "具体字段如 /summary、/facts/0/detail、/facts/0/active，跨字段问题可选择整条 /facts/0 或 /relations/0，遗漏问题可引用 /facts 或 /relations。"+
            "无需抄写 quote，由程序读取实际值。引用正文填写 quoteSource=BODY。人物怀疑与推断不能升级为确定事实。"; }
    protected JsonNode validateReview(Map<String,Object> task,JsonNode result,String body) { return validator().validate(result,body,delta(task)); }
}
