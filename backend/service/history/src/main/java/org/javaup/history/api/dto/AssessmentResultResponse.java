package org.javaup.history.api.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.javaup.history.api.dto.HistoryEnums.RecommendationLevel;
import org.javaup.history.api.dto.HistoryEnums.ResultGeneratedBy;
import org.javaup.history.entity.AssessmentResult;
import org.postgresql.util.PGobject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A non-diagnostic result record. It must preserve the visible safety notice.
 */
public record AssessmentResultResponse(
        @NotNull UUID assessmentId,
        @NotNull RecommendationLevel recommendationLevel,
        @NotBlank @Size(max = 2000) String summary,
        @NotNull @Size(max = 20) List<@Size(max = 500) String> reasons,
        @NotNull @Size(max = 20) List<@Size(max = 500) String> suggestedNextActions,
        @NotNull @Size(max = 20) List<@Size(max = 500) String> warningSigns,
        @NotBlank String safetyNotice,
        @NotNull ResultGeneratedBy generatedBy,
        @NotNull Instant finalizedAt
) {
    public static AssessmentResultResponse fromEntity(
            AssessmentResult entity,
            ObjectMapper objectMapper
    ) {
        if (entity.getSafetyNotice() == null || entity.getSafetyNotice().isBlank()) {
            throw new IllegalStateException("Stored assessment result is missing its safety notice");
        }
        return new AssessmentResultResponse(
                entity.getAssessmentId(),
                RecommendationLevel.valueOf(entity.getRecommendationLevel()),
                entity.getSummary(),
                readTextList(entity.getReasons(), objectMapper),
                readTextList(entity.getSuggestedNextActions(), objectMapper),
                readTextList(entity.getWarningSigns(), objectMapper),
                entity.getSafetyNotice(),
                ResultGeneratedBy.valueOf(entity.getGeneratedBy()),
                entity.getFinalizedAt().toInstant()
        );
    }

    private static List<String> readTextList(PGobject value, ObjectMapper objectMapper) {
        if (value == null || value.getValue() == null) {
            throw new IllegalStateException("Stored result list is missing");
        }
        JsonNode node = objectMapper.readTree(value.getValue());
        if (node == null || !node.isArray()) {
            throw new IllegalStateException("Stored result list must be a JSON array of strings");
        }
        List<String> items = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalStateException("Stored result list must contain only strings");
            }
            items.add(item.asText());
        }
        return List.copyOf(items);
    }
}
