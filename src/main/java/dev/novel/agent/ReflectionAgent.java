package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReflectionAgent implements NovelAgent {
    private final AgentContextService context;
    private final Store store;
    private final ModelClient model;
    private final Json json;
    public ReflectionAgent(AgentContextService context,Store store,ModelClient model,Json json) { this.context=context;this.store=store;this.model=model;this.json=json; }
    public Set<String> kinds() { return Set.of("REFLECT"); }
    public String purpose() { return "分析检查报告的证据和冲突，形成统一修改计划"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles(); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var reviews=store.list("tasks.reviews",task.get("novel_id"),task.get("chapter_no"),task.get("revision"),task.get("memory_version"));
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出：{majorChange:boolean,instructions:string,conflicts:[string]}。逐项处理有证据的问题，说明检查意见冲突的判断依据。"+
                "重大变更需要人工批准；保持通过检查的内容。上下文="+context.context(task,contextFiles())+
                "；正文="+json.write(context.draft(task))+"；独立检查报告="+json.write(reviews));
        if(!result.path("majorChange").isBoolean() || !result.path("conflicts").isArray()) throw new IllegalArgumentException("修改计划缺少 majorChange 或 conflicts");
        json.text(result,"instructions");return result;
    }
}
