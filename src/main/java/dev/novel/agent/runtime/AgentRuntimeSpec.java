package dev.novel.agent.runtime;

import java.util.Set;

public record AgentRuntimeSpec(String name, String description, Set<String> tools, int maxIterations) {
    public AgentRuntimeSpec {
        tools = Set.copyOf(tools);
        if (maxIterations < 1 || maxIterations > 24) throw new IllegalArgumentException("Agent 推理轮数必须为1至24");
    }

    public int maxRequests() {
        return (tools.isEmpty() ? 1 : maxIterations + 1) + 4;
    }
}
