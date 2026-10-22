package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface NovelAgent {
    default Set<String> tools() { return Set.of(); }
    default dev.novel.agent.runtime.AgentRuntimeSpec runtime() {
        return new dev.novel.agent.runtime.AgentRuntimeSpec(getClass().getSimpleName(),purpose(),tools(),tools().isEmpty()?1:2);
    }
    Set<String> kinds();
    String purpose();
    List<String> contextFiles();
    JsonNode execute(Map<String,Object> task) throws Exception;
}
