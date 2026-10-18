package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.dto.AgentOutputs;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReflectionAgent implements NovelAgent {
    private final AgentContextService context;
    private final Store store;
    private final ModelClient model;
    private final Json json;
    private final AgentOutputReader outputs;
    public ReflectionAgent(AgentContextService context,Store store,ModelClient model,Json json,AgentOutputReader outputs) { this.context=context;this.store=store;this.model=model;this.json=json;this.outputs=outputs; }
    public Set<String> kinds() { return Set.of("REFLECT"); }
    public String purpose() { return "分析检查报告的证据和冲突，形成统一修改计划"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles(); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var reviews=store.list("tasks.reviews",task.get("novel_id"),task.get("chapter_no"),task.get("revision"),task.get("memory_version"));
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出：{majorChange:boolean,instructions:string,conflicts:[string]}。逐项处理有证据的问题，说明检查意见冲突的判断依据。"+
                "重大变更需要人工批准；保持通过检查的内容。字数整理建议仅用于辅助处理具体内容问题，不为凑够或压缩字数单独安排重写。上下文="+context.context(task,contextFiles())+
                "；正文="+json.write(context.draft(task))+"；独立检查报告="+json.write(reviews));
        outputs.read(result,AgentOutputs.Reflection.class);return result;
    }
}
