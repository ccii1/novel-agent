package dev.novel.dto;

import jakarta.validation.constraints.*;

public record ObservationFeedback(@Min(1) @Max(5) int rating, @NotBlank @Size(max = 4000) String note) {
}
