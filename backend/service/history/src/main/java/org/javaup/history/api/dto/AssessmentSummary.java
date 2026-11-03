package org.javaup.history.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import org.javaup.history.api.dto.HistoryEnums.AssessmentStatus;
import org.javaup.history.api.dto.HistoryEnums.HandSide;
import org.javaup.history.api.dto.HistoryEnums.RecommendationLevel;

public record AssessmentSummary(
        @NotNull UUID assessmentId,
        @NotNull AssessmentStatus status,
        HandSide handSide,
        String locationLabel,
        String activityContext,
        RecommendationLevel recommendationLevel,
        @NotNull Instant updatedAt
) {
}
