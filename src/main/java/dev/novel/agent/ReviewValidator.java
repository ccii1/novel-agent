package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;

@Component
public class ReviewValidator {
    private final Json json;
    public ReviewValidator(Json json) { this.json=json; }
    public void validate(JsonNode review,String body) {
        if(!review.path("approved").isBoolean() || !review.path("majorChange").isBoolean() || !review.path("issues").isArray())
            throw new IllegalArgumentException("检查报告格式无效");
        if(review.get("approved").asBoolean()==!review.get("issues").isEmpty()) throw new IllegalArgumentException("检查结论与问题列表不一致");
        for(var issue:review.get("issues")) {
            String quote=json.text(issue,"quote");json.text(issue,"reason");json.text(issue,"suggestion");
            if(quote.length()<4 || !body.contains(quote)) throw new IllegalArgumentException("检查问题必须引用当前正文中的有效证据");
        }
    }
}
