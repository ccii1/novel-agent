package dev.novel.agent.runtime;

import dev.novel.agent.AgentContextService;
import dev.novel.repository.Store;
import dev.novel.service.AgentService;
import dev.novel.service.MemoryService;
import dev.novel.support.Json;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AgentRuntimeFactory {
    private final ObjectProvider<AgentService> agents;
    private final AgentContextService context;
    private final MemoryService memory;
    private final Store store;
    private final Json json;
    private final int toolResultBytes;
    public AgentRuntimeFactory(ObjectProvider<AgentService> agents,AgentContextService context,MemoryService memory,Store store,Json json,
            @Value("${novel.max-context-bytes}") int maxContext,@Value("${novel.agent-tool-result-bytes:12000}") int toolResultBytes) {
        this.agents=agents;this.context=context;this.memory=memory;this.store=store;this.json=json;
        if(toolResultBytes<1024) throw new IllegalArgumentException("工具返回预算至少为1024字节");
        this.toolResultBytes=Math.min(toolResultBytes,maxContext/10);
    }
    public AgentRuntimeSpec spec(String kind) { return agents.getObject().runtime(kind); }
    public void validateDelegation(Map<String,Object> task,Handle handle,String output) {
        if(!"COORDINATE".equals(task.get("kind"))) return;
        var selected=handle.tools().delegation();
        var result=json.read(output);
        if(selected==null || !selected.get("target").equals(result.path("target").asText()) || !selected.get("path").equals(result.path("path").asText()))
            throw new IllegalArgumentException("协调 Agent 必须通过委派工具选择处理角色，最终结果须与工具选择一致");
    }
    public record Handle(ReActAgent agent,StoryReadTools tools) implements AutoCloseable {
        public void close() { agent.close(); }
    }
    public Handle build(Map<String,Object> task,Model model,String system,GenerateOptions options,MiddlewareBase middleware) {
        var spec=spec(Store.string(task,"kind"));
        var tools=new StoryReadTools(task,context,memory,store,json,toolResultBytes);
        var toolkit=new Toolkit();
        if(!spec.tools().isEmpty()) {
            toolkit.registerTool(tools);
            if(!toolkit.getToolNames().containsAll(spec.tools())) throw new IllegalStateException("Agent 声明了不存在的工具");
            for(String name:Set.copyOf(toolkit.getToolNames())) if(!spec.tools().contains(name)) toolkit.removeTool(name);
        }
        store.event((String)task.get("novel_id"),Store.string(task,"id"),"AGENT_RUNTIME_BUILT",json.write(spec),(String)task.get("execution_id"));
        var agent=ReActAgent.builder().name(spec.name()).description(spec.description()).sysPrompt(system)
                .model(model).toolkit(toolkit).maxIters(spec.maxIterations()).generateOptions(options)
                .toolExecutionConfig(ExecutionConfig.builder().maxAttempts(1).build()).middleware(middleware).build();
        return new Handle(agent,tools);
    }
}
