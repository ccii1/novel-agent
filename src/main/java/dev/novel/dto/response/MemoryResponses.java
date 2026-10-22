package dev.novel.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

public final class MemoryResponses {
    private MemoryResponses() {}

    public record GraphNodeResponse(String id, String name) {}

    public record GraphEdgeResponse(
            String subject, String predicate, String object, int chapter, String evidence, String detail) {}

    public record GraphResponse(
            long version, List<GraphNodeResponse> nodes, List<GraphEdgeResponse> edges) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemoryResponse(
            String id, String novelId, String memoryKey, String kind, int chapterNo,
            long version, String content) {}

    // delta 和 review 保存经过校验的完整模型结果，保留证据来源等扩展字段。
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemoryReviewResponse(
            boolean pending, String taskId, Integer chapter, Integer revision, Long memoryVersion,
            JsonNode delta, JsonNode review, Integer repairAttempt, Integer automaticLimit) {}
}
