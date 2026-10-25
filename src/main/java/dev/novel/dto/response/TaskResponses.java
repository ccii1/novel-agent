package dev.novel.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import dev.novel.dto.response.NovelResponses.EventResponse;

// 查询记录沿用 snake_case；组合响应保持页面现有的 camelCase 字段。
public final class TaskResponses {
    private TaskResponses() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskResponse(
            String id, String novelId, String kind, String taskKey, String status,
            int chapterNo, int revision, long memoryVersion, String payload, String result,
            String error, Date availableAt, Date createdAt, Date updatedAt,
            String researchId, String provider, String modelName, String userId, String novelTitle,
            String reasoningEffort) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ObservedTaskResponse(
            String id, String novelId, String novelTitle, String kind, String status,
            int chapterNo, int revision, long memoryVersion, Date createdAt, Date updatedAt,
            String error, String callId, String callStatus, String model, String provider,
            String billingMode, BigDecimal actual, BigDecimal reserved, Long inputTokens,
            Long outputTokens, Long cachedInputTokens, Long latencyMs,
            Long executionCount, Long executionMs, Long interruptedCount, Integer humanRating) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProgressTaskResponse(
            String id, String kind, String status, String error, Date createdAt, Date updatedAt,
            String executionId, Date startedAt, Date finishedAt, Long elapsedMs, Long taskAgeMs,
            String callId, String callStatus, String provider, String model,
            BigDecimal actual, BigDecimal reserved, Long inputTokens, Long outputTokens, Long modelElapsedMs) {
    }

    public record TaskProgressResponse(
            ProgressTaskResponse task, String stage, List<EventResponse> events, int pollAfterMs) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionResponse(
            String id, String taskId, String status, String resultSource, Date startedAt, Date finishedAt,
            long queueMs, Long durationMs, String error, String result, Boolean approved,
            Integer issueCount, Boolean majorChange, Integer bodyCharacters, Integer factCount, Integer relationCount) {
    }

    // 用量、费用和耗时的 null 表示尚未取得结果，不能作为零费用或零耗时统计。
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelCallResponse(
            String id, String taskId, String novelId, Integer chapterNo, String billingMonth,
            String status, BigDecimal reserved, BigDecimal actual, Long inputTokens, Long outputTokens,
            String model, BigDecimal inputPrice, BigDecimal outputPrice, String response, String error,
            Date createdAt, Date finishedAt, Long latencyMs, String usageJson, Long cachedInputTokens,
            String executionId, String skillVersion, String skillFingerprint, String skillFiles,
            String requestBody, String provider, String billingMode, String agentFramework) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelTurnResponse(
            String id, String callId, String taskId, int turnNo, String status, String requestBody,
            String responseBody, Long inputTokens, Long outputTokens, Long cachedTokens,
            BigDecimal cost, Long latencyMs, Date createdAt, Date finishedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FeedbackResponse(long id, String taskId, int rating, String note, Date createdAt) {
    }

    public record TaskDetailResponse(
            TaskResponse task, List<ExecutionResponse> executions, List<EventResponse> events,
            List<FeedbackResponse> feedback, ModelCallResponse call, List<ModelTurnResponse> turns) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ObservationSummaryResponse(
            String kind, long taskCount, long completedTasks, long failedTasks, long runningTasks,
            long modelCalls, long inputTokens, long outputTokens, Long cachedInputTokens,
            long cacheReportedCalls, BigDecimal settledCost, BigDecimal pendingReservation,
            long executionCount, long interruptedCount, Long executionMs, long timedExecutions,
            Long queueMs, long passedReviews, long rejectedReviews, long issueCount,
            Long modelMs, long timedCalls, BigDecimal humanRating, long ratedTasks) {
    }

    public record ObservationDashboardResponse(
            List<ObservationSummaryResponse> summary, List<ObservedTaskResponse> tasks,
            long total, int page, int pageSize, String storage, String historicalTiming) {
    }
}
