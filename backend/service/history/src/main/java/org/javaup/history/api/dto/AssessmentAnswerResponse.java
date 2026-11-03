package org.javaup.history.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.javaup.history.api.dto.HistoryEnums.AssessmentAnswerSource;
import org.javaup.history.entity.AssessmentAnswer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A dynamic answer retains structured JSON but preserves its stable sequence.
 */
public record AssessmentAnswerResponse(
        @NotNull UUID answerId,
        @NotNull UUID clientAnswerId,
        @Positive int sequenceNumber,
        @NotBlank String questionKey,
        String questionText,
        @NotNull JsonNode answer,
        @NotNull AssessmentAnswerSource source,
        @NotNull Instant createdAt
) {
    public static AssessmentAnswerResponse fromEntity(
            AssessmentAnswer entity,
            ObjectMapper objectMapper
    ) {
        return new AssessmentAnswerResponse(
                entity.getId(),
                entity.getClientAnswerId(),
                entity.getSequenceNo(),
                entity.getQuestionKey(),
                entity.getQuestionText(),
                objectMapper.readTree(entity.getAnswer().getValue()),
                AssessmentAnswerSource.valueOf(entity.getAnswerSource()),
                entity.getCreatedAt().toInstant()
        );
    }
}
