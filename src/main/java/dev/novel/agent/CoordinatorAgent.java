package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.dto.AgentOutputs;
import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.service.CoordinationService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CoordinatorAgent implements NovelAgent {
    private final CoordinationService coordination;
    private final ModelClient model;
    private final AgentOutputReader outputs;
    private final Json json;
    public CoordinatorAgent(CoordinationService coordination,ModelClient model,AgentOutputReader outputs,Json json) { this.coordination=coordination;this.model=model;this.outputs=outputs;this.json=json; }
    public Set<String> kinds() { return Set.of("COORDINATE"); }
    public Set<String> tools() { return Set.of("read_plan","delegate_draft","delegate_outline","delegate_memory"); }
    public String purpose() { return "理解用户修改要求，选择正文、大纲或记忆处理 Agent，提出待确认的委派建议"; }
    public List<String> contextFiles() { return List.of("outline.md","current_state.md","pending_hooks.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        String id=Store.string(task,"novel_id");
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "根据当前可执行范围选择一个处理角色，必须调用且只调用一次 delegate_draft、delegate_outline 或 delegate_memory 工具。"+
                "工具只记录委派建议，人工确认后后端才会创建对应任务。不要声称修改已经完成。"+
                "最终输出 JSON {target:'DRAFT'|'OUTLINE'|'MEMORY',path:string,instructions:string,reason:string}，target 和 path 必须与工具选择一致；正文和记忆 path 为空字符串。"+
                "instructions 为明确的修改要求，reason 解释选择的角色和处理范围。用户要求="+Store.string(task,"payload")+"；当前可执行范围="+json.write(coordination.options(id)));
        outputs.read(result,AgentOutputs.Coordination.class);coordination.validate(id,result);return result;
    }
}
