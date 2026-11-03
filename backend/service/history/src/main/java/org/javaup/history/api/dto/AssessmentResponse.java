package org.javaup.history.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.javaup.history.api.dto.HistoryEnums.AssessmentStatus;
import org.javaup.history.api.dto.HistoryEnums.CurrentStepKey;
import org.javaup.history.api.dto.HistoryEnums.HandSide;
import org.javaup.history.api.dto.HistoryEnums.NormalUseImpact;
import org.javaup.history.api.dto.HistoryEnums.OnsetKind;
import org.javaup.history.api.dto.HistoryEnums.SymptomType;
import org.javaup.history.entity.Assessment;

/**
 * Transport aggregate for history-service. Its actual invariants and state
 * transitions intentionally remain learner-owned domain work.
 */
public record AssessmentResponse(
        @NotNull UUID assessmentId,
        @NotNull UUID userId,
        @NotNull AssessmentStatus status,
        @Valid InitialSymptomDetails initialInput,
        @NotNull List<@Valid SymptomLocationResponse> symptomLocations,
        @NotNull List<@Valid VisualReference> visualReferences,
        @NotNull List<@Valid AssessmentAnswerResponse> answers,
        @NotNull CurrentStepKey currentStepKey,
        @Positive int version,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt,
        Instant completedAt
) {
    public static AssessmentResponse fromEntity(
            Assessment entity,
            List<SymptomType> symptomTypes,
            List<SymptomLocationResponse> symptomLocations,
            List<VisualReference> visualReferences,
            List<AssessmentAnswerResponse> answers
    ) {
        InitialSymptomDetails initialInput = entity.getHandSide() == null
                ? null
                : new InitialSymptomDetails(
                HandSide.valueOf(entity.getHandSide()),
                symptomTypes == null || symptomTypes.isEmpty()
                ? null
                : List.copyOf(symptomTypes),
                OnsetKind.valueOf(entity.getOnsetKind()),
                entity.getOnsetAmount(),
                entity.getOnsetDate(),
                entity.getOnsetText(),
                entity.getTriggerContext(),
                NormalUseImpact.valueOf(entity.getNormalUseImpact()),
                entity.getAdditionalNote()
        );

        return new AssessmentResponse(
                entity.getId(),
                entity.getUserId(),
                AssessmentStatus.valueOf(entity.getStatus()),
                initialInput,
                List.copyOf(symptomLocations),
                List.copyOf(visualReferences),
                List.copyOf(answers),
                CurrentStepKey.valueOf(entity.getCurrentStepKey()),
                entity.getVersion(),
                entity.getCreatedAt().toInstant(),
                entity.getUpdatedAt().toInstant(),
                entity.getCompletedAt() == null
                        ? null
                        : entity.getCompletedAt().toInstant()
        );
    }
}
