package org.javaup.history.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.javaup.history.api.dto.HistoryEnums.AssessmentAnswerSource;
import tools.jackson.databind.JsonNode;

public record AppendAssessmentAnswerRequest(
        @NotNull UUID clientAnswerId,
        @Positive int sequenceNumber,
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_.-]{1,79}$") String questionKey,
        @Size(max = 500) String questionText,
        @NotNull JsonNode answer,
        @NotNull AssessmentAnswerSource source
) {
}
