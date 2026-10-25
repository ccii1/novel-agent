package dev.novel.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 在 HTTP 输出边界将查询结果映射为明确 DTO，只输出 DTO 声明的字段。
@Component
public class ResponseMapper {
    private final ObjectMapper mapper;

    public ResponseMapper(ObjectMapper mapper) {
        this.mapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public <T> T to(Map<String, ?> source, Class<T> type) {
        return mapper.convertValue(source, type);
    }

    public <T> List<T> list(List<? extends Map<String, ?>> source, Class<T> type) {
        return source.stream().map(row -> to(row, type)).toList();
    }
}
