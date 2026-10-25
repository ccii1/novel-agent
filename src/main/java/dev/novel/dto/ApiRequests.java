package dev.novel.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public final class ApiRequests {
    private ApiRequests() {
    }

    public record Registration(String username, String password) {
    }

    public record CoordinationRequest(@NotBlank @Size(max = 12000) String instructions) {
    }

    public record PlanChangeRequest(
            @NotBlank @Size(max = 200) String path,
            @NotBlank @Size(max = 12000) String instructions) {
    }

    public record PlanChangeApproval(boolean acknowledgeCore, boolean acknowledgeCompleted) {
    }

    public record CreateNovel(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 12000) String premise,
            @NotNull @Size(max = 12000) String bible,
            @Min(1) @Max(20) int stageSize,
            @NotNull @DecimalMin("0.01") @DecimalMax("300") BigDecimal novelBudget,
            @NotNull @DecimalMin("0.01") @DecimalMax("100") BigDecimal chapterBudget,
            @Size(max = 80) String provider,
            @Size(max = 200) String model, @Size(max = 20) String reasoningEffort) {
    }

    public record ModelChoice(
            @NotBlank @Size(max = 80) String provider,
            @Size(max = 200) String model, @Size(max = 20) String reasoningEffort) {
    }

    public record RetryTask(@Size(max = 80) String provider, @Size(max = 200) String model,
                            @Size(max = 20) String reasoningEffort) {
    }

    public record ResearchRequest(
            @Size(max = 5) List<@NotBlank @Size(max = 1500) String> urls,
            @Size(max = 4000) String brief,
            @Size(max = 80) String provider,
            @Size(max = 200) String model, @Size(max = 20) String reasoningEffort) {
    }

    public record ResearchMessage(
            @NotBlank @Size(max = 4000) String message,
            @Size(max = 80) String provider,
            @Size(max = 200) String model, @Size(max = 20) String reasoningEffort) {
    }

    public record Revision(
            @NotBlank @Size(max = 12000) String instructions,
            JsonNode plan, boolean useCurrentPlan) {
    }

    public record MemoryRepair(
            @NotBlank String taskId,
            @NotBlank @Size(max = 12000) String instructions,
            JsonNode delta) {
    }

    public record Edit(@Min(1) int chapter, @NotBlank @Size(max = 12000) String instructions) {
    }

    public record EditApproval(boolean acknowledgePublished) {
    }

    public record Reconcile(@NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal actual) {
    }

    public record Published(boolean published) {
    }
}
