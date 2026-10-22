package dev.novel.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import dev.novel.dto.response.MemoryResponses.*;
import dev.novel.dto.response.TaskResponses.TaskResponse;

public final class NovelResponses {
    private NovelResponses() {}

    // 数据行字段保持现有 snake_case，业务聚合响应使用 camelCase。
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NovelResponse(
            String id, String title, String premise, String bible, String status,
            int stageNo, int chapterNo, int stageSize, long memoryVersion,
            String plan, String reflection, BigDecimal novelBudget, BigDecimal chapterBudget,
            String error, String resumeStatus, Date createdAt, String provider,
            String modelName, String userId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NovelSummaryResponse(
            String id, String title, String status, int stageNo, int chapterNo, long memoryVersion) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NovelSnapshotResponse(
            String id, String status, int stageNo, int chapterNo, long memoryVersion, long eventCursor) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChapterResponse(
            String novelId, int number, int stageNo, int revision, String title, String body,
            String summary, String status, long memoryVersion, boolean published) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChapterSummaryResponse(
            int number, int stageNo, int revision, String title, String status,
            long memoryVersion, boolean published, String summary) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventResponse(
            long id, String novelId, String taskId, String type, String message,
            Date createdAt, String executionId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EditResponse(
            String id, String novelId, int chapterNo, long baseVersion,
            String instruction, String impact, String status, Date createdAt) {}

    public record AffectedChapterResponse(int number, String title, boolean published) {}

    public record EditImpactResponse(
            List<AffectedChapterResponse> chapters, List<MemoryResponse> memories, String reason) {}

    public record EditCreatedResponse(String id, EditImpactResponse impact) {}

    public record NovelDetailResponse(
            NovelResponse novel, List<ChapterSummaryResponse> chapters, List<TaskResponse> tasks,
            List<EventResponse> events, List<EditResponse> edits, MemoryReviewResponse memoryReview) {}
}
