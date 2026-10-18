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
        for(int index=0;index<output.issues().size();index++) {
            var issue=output.issues().get(index);
            if(!body.contains(issue.quote())) throw new IllegalArgumentException(
                    "检查输出格式错误：第"+(index+1)+"条问题的 quote 无法在当前正文中连续找到。"+
                    "引用必须逐字复制，不能拼接多处原文、添加省略号或改写标点。模型响应已保存；本次检查未通过证据校验。");
        }
    }
}
