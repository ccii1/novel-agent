package dev.novel.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Date;
import java.util.List;

import dev.novel.dto.response.TaskResponses.TaskResponse;
import dev.novel.dto.response.SystemResponses.ModelChoiceResponse;

public final class ResearchResponses {
    private ResearchResponses() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResearchReportResponse(
            String id, String taskId, String sources, String ideas, Date createdAt, String brief, String userId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResearchMessageResponse(
            long id, String researchId, String role, String content, String ideas, String taskId, Date createdAt) {
    }

    public record ResearchDetailResponse(
            ResearchReportResponse report, List<ResearchMessageResponse> messages,
            List<TaskResponse> tasks, ModelChoiceResponse modelChoice) {
    }
}
