package dev.novel.agent;

import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryCheckAgent extends ReviewAgent {
    public MemoryCheckAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator) { super(context,model,json,validator); }
    public Set<String> kinds() { return Set.of("CHECK_MEMORY"); }
    public String purpose() { return "记忆和关系增量是否得到正文支持，是否错误覆盖历史设定，是否遗漏重要状态变化"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md"); }
    protected String additionalInput(Map<String,Object> task,String body) { return "；待检查记忆="+json.write(json.read(Store.string(task,"payload"))); }
}
