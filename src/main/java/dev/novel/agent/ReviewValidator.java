package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.dto.AgentOutputs;
import org.springframework.stereotype.Component;

@Component
public class ReviewValidator {
    private final AgentOutputReader outputs;
    public ReviewValidator(AgentOutputReader outputs) { this.outputs=outputs; }
    public void validate(JsonNode review,String body) {
        var output=outputs.read(review,AgentOutputs.Review.class);
        if(output.approved()!=output.issues().isEmpty()) throw new IllegalArgumentException("检查结论与问题列表不一致");
        for(var issue:output.issues()) {
            if(!body.contains(issue.quote())) throw new IllegalArgumentException("检查问题必须引用当前正文中的有效证据");
        }
    }
}
