package dev.novel.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.dto.AgentOutputs;
import org.springframework.stereotype.Component;

@Component
public class ChapterOutputValidator {
    private final AgentOutputReader outputs;
    public ChapterOutputValidator(AgentOutputReader outputs) { this.outputs=outputs; }
    public AgentOutputs.Chapter validate(JsonNode result) {
        var generation=outputs.read(result,AgentOutputs.ChapterGeneration.class);
        if("BLOCKED".equals(generation.status())) {
            if(generation.reason()==null || generation.reason().isBlank()) throw new IllegalArgumentException("正文未生成时需要说明原因");
            throw new IllegalStateException("正文未生成："+generation.reason());
        }
        return outputs.read(result,AgentOutputs.Chapter.class);
    }
}
