package org.javaup.ai.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentEnums.AgentBehavior;
import org.javaup.ai.api.dto.AgentEnums.AgentSessionStatus;
import org.javaup.ai.entity.AgentCheckpoint;
import org.javaup.ai.entity.AgentSession;
import org.javaup.ai.entity.AgentStep;

/** 当前分支视图；读取不启动模型或隐式恢复任务。 */
public record AgentSessionResponse(
        UUID sessionId,
        UUID assessmentId,
        AgentSessionStatus status,
        int currentStepNumber,
        AgentBehavior behavior,
        AgentQuestion question,
        List<AgentStepSummary> recentSteps,
        String terminationReason,
        Instant updatedAt,
        long version,
        UUID activeCheckpointId,
        String modelProvider,
        String modelName,
        int totalModelCalls,
        int maxModelCalls,
        int maxSteps
) {

    public static AgentSessionResponse fromEntity(
            AgentSession session,
            AgentCheckpoint point,
            AgentBehavior behavior,
            AgentQuestion question,
            List<AgentStep> activePath
    ) {
        int start = Math.max(0, activePath.size() - 20);
        int currentStepNumber = activePath.isEmpty() ? 0 : activePath.getLast().getStepNo();
        List<AgentStepSummary> recentSteps = activePath.subList(start, activePath.size()).stream()
                .map(AgentStepSummary::fromEntity)
                .toList();

        return new AgentSessionResponse(
                session.getId(),
                session.getAssessmentId(),
                AgentSessionStatus.valueOf(session.getStatus()),
                currentStepNumber,
                behavior,
                question,
                recentSteps,
                session.getTerminationDetail(),
                session.getUpdatedAt().toInstant(),
                session.getVersion(),
                point.getId(),
                session.getModelProvider(),
                session.getModelName(),
                session.getTotalModelCalls(),
                session.getMaxModelCalls(),
                session.getMaxSteps());
    }
}
