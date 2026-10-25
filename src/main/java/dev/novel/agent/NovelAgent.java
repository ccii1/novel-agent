package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface NovelAgent {
    default Set<String> tools() {
        return Set.of();
    }

    default dev.novel.agent.runtime.AgentRuntimeSpec runtime() {
        var allowed = new java.util.HashSet<>(tools());
        if (allowed.contains("read_memory"))
            allowed.addAll(Set.of("list_memory_files", "search_memory", "lookup_memory", "read_source", "read_candidate_memory", "read_memory_batch"));
        return new dev.novel.agent.runtime.AgentRuntimeSpec(getClass().getSimpleName(), purpose(), allowed, allowed.isEmpty() ? 1 : 2);
    }

    Set<String> kinds();

    String purpose();

    List<String> contextFiles();

    JsonNode execute(Map<String, Object> task) throws Exception;
}
