package dev.novel.agent.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
public class AgentOutputReader {
    private final ObjectMapper mapper;
    private final Validator validator;
    public AgentOutputReader(ObjectMapper mapper,Validator validator) {
        this.mapper=mapper.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail);
        this.mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString,CoercionAction.Fail);
        this.mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString,CoercionAction.Fail);
        this.validator=validator;
    }
    public <T> T read(JsonNode node,Class<T> type) {
        if(node==null || !node.isObject()) throw new IllegalArgumentException("Agent 输出需要 JSON 对象："+type.getSimpleName());
        final T output;
        try { output=mapper.treeToValue(node,type); }
        catch(JsonProcessingException error) {
            throw new IllegalArgumentException("Agent 输出字段类型无效："+type.getSimpleName(),error);
        }
        var violations=validator.validate(output);
        if(!violations.isEmpty()) {
            String fields=violations.stream().map(violation -> violation.getPropertyPath().toString()).distinct().sorted().collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Agent 输出字段不符合要求："+type.getSimpleName()+" ["+fields+"]");
        }
        return output;
    }
}
