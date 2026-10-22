package dev.novel.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

public final class QualityResponses {
    private QualityResponses() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChapterTotalsResponse(long chapters, long storedCharacters) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChapterQualityResponse(
            String novelId, int chapterNo, int revision, int characters, int totalLines,
            int dialogueLines, double dialogueRatio, String pacingData) {}

    public record QualityDashboardResponse(
            ChapterTotalsResponse totals, List<ChapterQualityResponse> recent,
            boolean dialogueRising, String measurement) {}
}
