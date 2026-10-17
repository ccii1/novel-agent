package dev.novel.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("JSON 序列化失败", e); }
    }
    public JsonNode read(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("JSON 格式无效", e); }
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public String text(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.get(field).isTextual() || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("缺少文本字段：" + field);
        }
        return node.get(field).asText();
    }
}
