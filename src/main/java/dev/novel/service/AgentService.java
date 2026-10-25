package dev.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.agent.NovelAgent;
import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.AgentMetricsMapper;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentService {
    private final Map<String, NovelAgent> registry;
    private final ModelClient model;
    private final Store store;
    private final Json json;
    private final AgentMetricsMapper metrics;
    private final org.springframework.core.env.Environment environment;

    public AgentService(List<NovelAgent> agents, ModelClient model, Store store, Json json, AgentMetricsMapper metrics, org.springframework.core.env.Environment environment) {
        if (agents.size() > 6) throw new IllegalStateException("业务 Agent 组件数量不能超过6个");
        Map<String, NovelAgent> registered = new TreeMap<>();
        for (var agent : agents)
            for (String kind : agent.kinds()) {
                if (registered.putIfAbsent(kind, agent) != null)
                    throw new IllegalStateException("Agent 任务类型重复：" + kind);
            }
        Set<String> expected = Set.of("COORDINATE", "PLAN", "PLAN_CHANGE", "WRITE", "REWRITE", "CHECK_CHARACTER", "CHECK_PLOT", "CHECK_PACING", "CHECK_STYLE",
                "REFLECT", "MEMORY", "MEMORY_REPAIR", "CHECK_MEMORY", "CHECK_CHAPTER", "RESEARCH", "RESEARCH_CHAT");
        if (!registered.keySet().equals(expected)) throw new IllegalStateException("Agent 注册类型与业务流程不一致");
        this.registry = Map.copyOf(registered);
        this.model = model;
        this.store = store;
        this.json = json;
        this.metrics = metrics;
        this.environment = environment;
        expected.forEach(this::runtime);
    }

    public JsonNode execute(Map<String, Object> task) throws Exception {
        String kind = Store.string(task, "kind");
        var agent = registry.get(kind);
        if (agent == null) throw new IllegalArgumentException("未知 Agent 类型：" + kind);
        if (task.get("result") != null && !"CHECK_MEMORY".equals(kind)) {
            model.validateCheckpoint(task);
            return json.read((String) task.get("result"));
        }
        String identity = agent.getClass().getSimpleName();
        store.event((String) task.get("novel_id"), (String) task.get("id"), "AGENT_STARTED", json.write(Map.of("agent", identity, "kind", kind,
                "revision", task.get("revision"), "memoryVersion", task.get("memory_version"), "contextFiles", agent.contextFiles())), (String) task.get("execution_id"));
        var result = agent.execute(Collections.unmodifiableMap(new LinkedHashMap<>(task)));
        store.event((String) task.get("novel_id"), (String) task.get("id"), "AGENT_FINISHED", identity, (String) task.get("execution_id"));
        return result;
    }

    public List<Map<String, Object>> catalogue() {
        return registry.values().stream().distinct().sorted(Comparator.comparing(agent -> agent.getClass().getSimpleName())).map(agent -> {
            var kinds = agent.kinds().stream().sorted().toList();
            String primary = kinds.contains("WRITE") ? "WRITE" : kinds.contains("CHECK_CHAPTER") ? "CHECK_CHAPTER" : kinds.contains("MEMORY") ? "MEMORY" : kinds.get(0);
            return Map.<String, Object>of("kind", primary, "kinds", kinds, "agent", agent.getClass().getSimpleName(),
                    "purpose", agent.purpose(), "contextFiles", agent.contextFiles(), "contextScope", "task", "runtime", runtime(primary));
        }).toList();
    }

    public dev.novel.agent.runtime.AgentRuntimeSpec runtime(String kind) {
        var agent = registry.get(kind);
        if (agent == null) throw new IllegalArgumentException("未知 Agent 类型：" + kind);
        var spec = agent.runtime();
        int rounds = spec.tools().isEmpty() ? 1 : environment.getProperty("novel.agent-tool-rounds." + kind.toLowerCase(Locale.ROOT), Integer.class,
                environment.getProperty("novel.agent-tool-rounds.default", Integer.class, 8));
        return new dev.novel.agent.runtime.AgentRuntimeSpec(spec.name(), spec.description(), spec.tools(), rounds);
    }

    public List<Map<String, Object>> metrics(String novelId) {
        if (novelId != null) store.novel(novelId);
        return metrics.summary(novelId);
    }
}
