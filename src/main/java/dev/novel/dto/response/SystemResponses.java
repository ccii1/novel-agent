package dev.novel.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dev.novel.agent.runtime.AgentRuntimeSpec;
import dev.novel.dto.response.TaskResponses.ModelCallResponse;

import java.math.BigDecimal;
import java.util.List;

public final class SystemResponses {
    private SystemResponses() {
    }

    public record EngineStatusResponse(int workers, long active, boolean ready) {
    }

    public record SkillStatusResponse(
            String name, String version, String commit, boolean required, String mainFingerprint) {
    }

    public record SystemStatusResponse(
            EngineStatusResponse engine, boolean modelConfigured, String timezone,
            SkillStatusResponse skill, String agentFramework, String persistence,
            String rateLimiter, boolean retryModelSelection, String database) {
    }

    public record AgentResponse(
            String kind, List<String> kinds, String agent, String purpose, List<String> contextFiles,
            String contextScope, AgentRuntimeSpec runtime) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentMetricsResponse(
            String kind, long taskCount, long completedTasks, long failedTasks, long runningTasks,
            long waitingTasks, long cancelledTasks, long modelCalls, long inputTokens, long outputTokens,
            BigDecimal settledCost, BigDecimal pendingReservation, long uncertainCalls) {
    }

    public record ProviderResponse(String id, String label, String defaultModel, boolean ready, String billingMode,
                                   List<String> reasoningEfforts, String defaultReasoningEffort) {
    }

    public record ProviderCatalogueResponse(String defaultProvider, List<ProviderResponse> providers) {
    }

    public record ModelChoiceResponse(String provider, String model, String reasoningEffort) {
    }

    public record BudgetResponse(
            String month, BigDecimal limit, BigDecimal usedAndReserved,
            BigDecimal remaining, List<ModelCallResponse> calls) {
    }
}
