package org.javaup.ai.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.javaup.ai.api.dto.AgentEnums.RecommendationLevel;
import org.javaup.ai.entity.AgentSession;
import org.javaup.ai.model.AgentDecision;

/**
 * Agent output candidate. It is not history-service's final business
 * record and must not be treated as a diagnosis.
 */
public record AgentOutcomeResponse(
        @NotNull UUID sessionId,
        @NotNull UUID assessmentId,
        @NotNull RecommendationLevel recommendationLevel,
        @NotBlank String summary,
        @NotNull List<String> reasons,
        @NotNull List<String> suggestedNextActions,
        @NotNull List<String> warningSigns,
        @NotBlank String safetyNotice,
        @NotNull Instant finalizedAt,
        String uncertaintyNote,
        List<AgentDecision.Reference> references
) {

    public AgentOutcomeResponse {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public AgentOutcomeResponse(
            UUID sessionId,
            UUID assessmentId,
            RecommendationLevel recommendationLevel,
            String summary,
            List<String> reasons,
            List<String> suggestedNextActions,
            List<String> warningSigns,
            String safetyNotice,
            Instant finalizedAt,
            String uncertaintyNote
    ) {
        this(
                sessionId,
                assessmentId,
                recommendationLevel,
                summary,
                reasons,
                suggestedNextActions,
                warningSigns,
                safetyNotice,
                finalizedAt,
                uncertaintyNote,
                List.of());
    }

    public static AgentOutcomeResponse fromDecision(
            AgentSession session,
            AgentDecision.OutcomeDraft draft,
            String safetyNotice,
            Instant finalizedAt
    ) {
        return fromDecision(session, draft, safetyNotice, finalizedAt, List.of());
    }

    public static AgentOutcomeResponse fromDecision(
            AgentSession session,
            AgentDecision.OutcomeDraft draft,
            String safetyNotice,
            Instant finalizedAt,
            List<AgentDecision.Reference> references
    ) {
        return new AgentOutcomeResponse(
                session.getId(),
                session.getAssessmentId(),
                draft.recommendationLevel(),
                draft.summary(),
                List.copyOf(draft.reasons()),
                List.copyOf(draft.suggestedNextActions()),
                List.copyOf(draft.warningSigns()),
                safetyNotice,
                finalizedAt,
                draft.uncertaintyNote(),
                references);
    }
}
