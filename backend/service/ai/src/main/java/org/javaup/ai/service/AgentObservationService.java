package org.javaup.ai.service;

import static org.javaup.ai.api.problem.AIErrorCode.AGENT_SESSION_NOT_FOUND;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentObservationResponse;
import org.javaup.ai.api.dto.AgentObservationResponse.CallObservation;
import org.javaup.ai.api.dto.AgentObservationResponse.StepObservation;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.entity.AgentCheckpoint;
import org.javaup.ai.entity.AgentSession;
import org.javaup.ai.entity.AgentStep;
import org.javaup.ai.mapper.AgentCheckpointMapper;
import org.javaup.ai.mapper.AgentSessionMapper;
import org.javaup.ai.mapper.AgentStepMapper;
import org.javaup.ai.mapper.ModelCallMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** A read-only view over existing workflow records; no extra event-sourcing tables. */
@Service
public class AgentObservationService {

    private final AgentSessionMapper sessions;
    private final AgentStepMapper steps;
    private final AgentCheckpointMapper checkpoints;
    private final ModelCallMapper calls;

    public AgentObservationService(
            AgentSessionMapper sessions,
            AgentStepMapper steps,
            AgentCheckpointMapper checkpoints,
            ModelCallMapper calls
    ) {
        this.sessions = sessions;
        this.steps = steps;
        this.checkpoints = checkpoints;
        this.calls = calls;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentObservationResponse getObservations(UUID userId, UUID sessionId) {
        AgentSession session = sessionId == null ? null : sessions.selectById(sessionId);
        if (userId == null || session == null || !userId.equals(session.getUserId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, AGENT_SESSION_NOT_FOUND, "Analysis task does not exist");
        }

        List<AgentStep> sessionSteps = steps.selectSessionSteps(sessionId);
        AgentCheckpoint point = session.getActiveCheckpointId() == null
                ? null : checkpoints.selectById(session.getActiveCheckpointId());
        Map<UUID, AgentStep> byId = new HashMap<>();
        sessionSteps.forEach(step -> byId.put(step.getId(), step));
        Set<UUID> activeBranch = new HashSet<>();
        UUID cursor = point != null && sessionId.equals(point.getSessionId()) ? point.getStepId() : null;

        while (cursor != null && activeBranch.add(cursor)) {
            AgentStep step = byId.get(cursor);
            cursor = step == null ? null : step.getParentStepId();
        }

        List<StepObservation> stepViews = sessionSteps.stream()
                .map(step -> new StepObservation(
                        step.getId(),
                        step.getParentStepId(),
                        step.getStepNo(),
                        step.getStepType(),
                        step.getStatus(),
                        activeBranch.contains(step.getId()),
                        step.getStartedAt(),
                        step.getCompletedAt()))
                .toList();
        List<CallObservation> callViews = calls.selectSessionCalls(sessionId).stream()
                .map(call -> new CallObservation(
                        call.getId(),
                        call.getStepId(),
                        call.getBaseCheckpointId(),
                        call.getCallNo(),
                        call.getExecutionGeneration(),
                        call.getProvider(),
                        call.getModelName(),
                        call.getStatus(),
                        call.getTraceId(),
                        call.getLatencyMs(),
                        call.getInputTokens(),
                        call.getOutputTokens(),
                        call.getTotalTokens(),
                        call.getErrorCategory(),
                        call.getErrorCode(),
                        call.getStartedAt(),
                        call.getCompletedAt()))
                .toList();

        return new AgentObservationResponse(
                sessionId, session.getStatus(), session.getActiveCheckpointId(), stepViews, callViews);
    }
}
