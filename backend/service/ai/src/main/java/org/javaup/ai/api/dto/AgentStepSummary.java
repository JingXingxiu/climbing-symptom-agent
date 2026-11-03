package org.javaup.ai.api.dto;

import java.time.Instant;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentEnums.AgentStepType;
import org.javaup.ai.entity.AgentStep;

public record AgentStepSummary(
        int stepNumber,
        AgentStepType stepType,
        String message,
        Instant createdAt,
        UUID stepId,
        UUID parentStepId,
        String status
) {

    public static AgentStepSummary fromEntity(AgentStep step) {
        return new AgentStepSummary(
                step.getStepNo(),
                AgentStepType.valueOf(step.getStepType()),
                step.getUserVisibleMessage(),
                step.getStartedAt().toInstant(),
                step.getId(),
                step.getParentStepId(),
                step.getStatus());
    }
}
