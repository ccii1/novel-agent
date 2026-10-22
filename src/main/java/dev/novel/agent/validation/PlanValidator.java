package dev.novel.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.dto.AgentOutputs;
import org.springframework.stereotype.Component;

@Component
public class PlanValidator {
    private final AgentOutputReader outputs;
    public PlanValidator(AgentOutputReader outputs) { this.outputs=outputs; }
    public void validate(JsonNode plan,int first,int size) {
        var output=outputs.read(plan,AgentOutputs.Plan.class);
        if(output.chapters().size()!=size) throw new IllegalArgumentException("大纲章节数量无效");
        for(var chapter:output.chapters()) {
            if(chapter.number()!=first++) throw new IllegalArgumentException("大纲章节编号必须连续");
        }
    }
}
