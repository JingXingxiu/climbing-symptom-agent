package org.javaup.ai.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Owner-visible execution metadata. Excludes symptoms, answers, prompts and picture URLs. */
public record AgentObservationResponse(
        UUID sessionId,
        String status,
        UUID activeCheckpointId,
        List<StepObservation> steps,
        List<CallObservation> modelCalls
) {

    public record StepObservation(
            UUID stepId,
            UUID parentStepId,
            int stepNumber,
            String stepType,
            String status,
            boolean activeBranch,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {}

    public record CallObservation(
            UUID callId,
            UUID stepId,
            UUID baseCheckpointId,
            int callNumber,
            long executionGeneration,
            String provider,
            String model,
            String status,
            String traceId,
            Integer executionLatencyMs,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            String errorCategory,
            String errorCode,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {}
}
