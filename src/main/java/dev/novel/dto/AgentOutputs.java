package dev.novel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class AgentOutputs {
    public record PlanChange(@NotNull com.fasterxml.jackson.databind.JsonNode replacement,@NotBlank String reason,
            @NotNull Boolean coreChange,@NotNull List<@NotNull @Valid PlanImpact> impacts) {}
    public record PlanImpact(@NotNull @Positive Integer chapter,@NotBlank String reason) {}
    public record Plan(@NotBlank String stageGoal,@NotEmpty List<@NotBlank String> coreMilestones,
            @NotEmpty List<@NotNull @Valid PlannedChapter> chapters) {}
    public record PlannedChapter(@NotNull @Positive Integer number,@NotBlank String title,@NotBlank String goal,
            @NotNull List<@NotBlank String> characters,@NotNull List<@NotBlank String> foreshadowIds,
            String sceneType,Boolean climax) {}
    public record Chapter(@NotBlank String title,@NotBlank String body) {}
    public record Review(@NotNull Boolean approved,@NotNull Boolean majorChange,
            @NotNull List<@NotNull @Valid Issue> issues) {}
    public record Dimensions(@NotNull Boolean character,@NotNull Boolean plot,@NotNull Boolean pacing,@NotNull Boolean style) {}
    public record Issue(String severity,@NotBlank @Size(min=4) String quote,@NotBlank String reason,@NotBlank String suggestion) {}
    public record Narrative(@NotBlank String opening,@NotBlank String activity,@NotBlank String ending,
            @NotBlank String resolution,@NotBlank String pattern,@NotNull Boolean climax,@NotNull Boolean combat) {}
    public record Reflection(@NotNull Boolean majorChange,@NotBlank String instructions,@NotNull List<@NotBlank String> conflicts) {}
    public record MemoryDelta(@NotBlank @Size(max=1200) String summary,@NotNull List<@NotNull @Valid Fact> facts,
            @NotNull List<@NotNull @Valid Relation> relations) {}
    public record Fact(@NotBlank @Size(max=350) String key,@NotBlank @Pattern(regexp="WORLD|CHARACTER|EVENT|FORESHADOW") String kind,
            @NotBlank String name,String detail,Boolean active,@Positive Integer dueChapter,@NotBlank @Size(min=4) String evidence) {}
    public record Relation(@NotBlank String subject,@NotBlank String predicate,@NotBlank String object,
            String detail,Boolean deleted,@NotBlank @Size(min=4) String evidence) {}
    public record Research(@NotBlank @Size(max=6000) String reply,@NotNull @Size(min=3,max=3) List<@NotNull @Valid Idea> ideas) {}
    public record Idea(@NotBlank String title,@NotBlank String audience,@NotBlank String genre,@NotBlank String premise,
            @NotBlank String hook,@NotBlank String longTermConflict,@NotNull List<@NotBlank String> risks,
            @NotNull List<@NotBlank String> sources) {}
    private AgentOutputs() {}
}
