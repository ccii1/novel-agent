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
    private final Map<String,NovelAgent> registry;
    private final ModelClient model;
    private final Store store;
    private final Json json;
    private final AgentMetricsMapper metrics;
    public AgentService(List<NovelAgent> agents,ModelClient model,Store store,Json json,AgentMetricsMapper metrics) {
        Map<String,NovelAgent> registered=new TreeMap<>();
        for(var agent:agents) for(String kind:agent.kinds()) {
            if(registered.putIfAbsent(kind,agent)!=null) throw new IllegalStateException("Agent 任务类型重复："+kind);
        }
        Set<String> expected=Set.of("PLAN","WRITE","REWRITE","CHECK_CHARACTER","CHECK_PLOT","CHECK_PACING","CHECK_STYLE",
                "REFLECT","MEMORY","CHECK_MEMORY","RESEARCH","RESEARCH_CHAT");
        if(!registered.keySet().equals(expected)) throw new IllegalStateException("Agent 注册类型与业务流程不一致");
        this.registry=Map.copyOf(registered);this.model=model;this.store=store;this.json=json;
        this.metrics=metrics;
    }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        String kind=Store.string(task,"kind");
        var agent=registry.get(kind);
        if(agent==null) throw new IllegalArgumentException("未知 Agent 类型："+kind);
        if(task.get("result")!=null) { model.validateCheckpoint(task);return json.read((String)task.get("result")); }
        String identity=agent.getClass().getSimpleName();
        store.event((String)task.get("novel_id"),(String)task.get("id"),"AGENT_STARTED",json.write(Map.of("agent",identity,"kind",kind,
                "revision",task.get("revision"),"memoryVersion",task.get("memory_version"),"contextFiles",agent.contextFiles())),(String)task.get("execution_id"));
        var result=agent.execute(Collections.unmodifiableMap(new LinkedHashMap<>(task)));
        store.event((String)task.get("novel_id"),(String)task.get("id"),"AGENT_FINISHED",identity,(String)task.get("execution_id"));
        return result;
    }
    public List<Map<String,Object>> catalogue() {
        return registry.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var agent=entry.getValue();
            return Map.<String,Object>of("kind",entry.getKey(),"agent",agent.getClass().getSimpleName(),
                    "purpose",agent.purpose(),"contextFiles",agent.contextFiles(),"contextScope","task");
        }).toList();
    }
    public List<Map<String,Object>> metrics(String novelId) {
        if(novelId!=null) store.novel(novelId);
        return metrics.summary(novelId);
    }
}
