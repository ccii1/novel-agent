package dev.novel.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Date;
import java.util.List;

public final class PlanResponses {
    private PlanResponses() {
    }

    // 大纲字段可以是文本或数组，JSON 值保留原始类型。
    public record PlanTargetResponse(String path, String label, JsonNode value) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlanChangeResponse(
            String id, String novelId, String taskId, int stageNo, long baseMemory, int baseChapter,
            String targetPath, String instructions, String suggestion, String status,
            Date createdAt, Date appliedAt, String taskStatus, String taskError,
            String targetLabel, boolean stale) {
    }

    public record PlanChangesResponse(
            boolean editable, List<PlanTargetResponse> targets, List<PlanChangeResponse> changes) {
    }
}
