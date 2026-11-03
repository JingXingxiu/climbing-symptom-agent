package org.javaup.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentObservationResponse;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.entity.AgentCheckpoint;
import org.javaup.ai.entity.AgentSession;
import org.javaup.ai.entity.AgentStep;
import org.javaup.ai.entity.ModelCall;
import org.javaup.ai.mapper.AgentCheckpointMapper;
import org.javaup.ai.mapper.AgentSessionMapper;
import org.javaup.ai.mapper.AgentStepMapper;
import org.javaup.ai.mapper.ModelCallMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import tools.jackson.databind.json.JsonMapper;

class AgentObservationServiceTest {

    private final AgentSessionMapper sessions = mock(AgentSessionMapper.class);
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final AgentCheckpointMapper checkpoints = mock(AgentCheckpointMapper.class);
    private final ModelCallMapper calls = mock(ModelCallMapper.class);
    private final AgentObservationService service = new AgentObservationService(sessions, steps, checkpoints, calls);

    @Test
    void deniesAnotherOwnerBeforeReadingAnyExecutionData() {
        UUID sessionId = UUID.randomUUID();
        AgentSession session = new AgentSession();
        session.setUserId(UUID.randomUUID());
        when(sessions.selectById(sessionId)).thenReturn(session);

        assertThatThrownBy(() -> service.getObservations(UUID.randomUUID(), sessionId))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status().value()).isEqualTo(404));
        verifyNoInteractions(steps, checkpoints, calls);
    }

    @Test
    void marksCurrentBranchAndNeverSerializesSensitivePayloads() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setStatus("WAITING_FOR_USER");
        session.setActiveCheckpointId(UUID.randomUUID());
        when(sessions.selectById(sessionId)).thenReturn(session);

        AgentStep root = step(1, null);
        AgentStep obsolete = step(2, root.getId());
        AgentStep current = step(3, root.getId());
        PGobject privateAnswer = new PGobject();
        privateAnswer.setType("jsonb");
        privateAnswer.setValue("{\"answer\":\"PRIVATE_SYMPTOM\"}");
        current.setAnswer(privateAnswer);
        current.setUserVisibleMessage("PRIVATE_SYMPTOM");
        when(steps.selectSessionSteps(sessionId)).thenReturn(List.of(root, obsolete, current));

        AgentCheckpoint point = new AgentCheckpoint();
        point.setSessionId(sessionId);
        point.setStepId(current.getId());
        when(checkpoints.selectById(session.getActiveCheckpointId())).thenReturn(point);

        ModelCall call = new ModelCall();
        call.setId(UUID.randomUUID());
        call.setCallNo(1);
        call.setExecutionGeneration(2L);
        call.setTraceId("0123456789abcdef0123456789abcdef");
        call.setStatus("DISCARDED");
        call.setResponseMetadata(privateAnswer);
        when(calls.selectSessionCalls(sessionId)).thenReturn(List.of(call));

        AgentObservationResponse response = service.getObservations(userId, sessionId);

        assertThat(response.steps()).extracting(AgentObservationResponse.StepObservation::activeBranch)
                .containsExactly(true, false, true);
        assertThat(response.modelCalls().getFirst().status()).isEqualTo("DISCARDED");
        assertThat(response.modelCalls().getFirst().traceId()).isEqualTo(call.getTraceId());
        String serialized = JsonMapper.builder().findAndAddModules().build().writeValueAsString(response);
        assertThat(serialized).doesNotContain("PRIVATE_SYMPTOM", "responseMetadata", "answer", "prompt");
    }

    @Test
    void newTaskHasAnEmptyObservationHistory() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        when(sessions.selectById(sessionId)).thenReturn(session);
        when(steps.selectSessionSteps(sessionId)).thenReturn(List.of());
        when(calls.selectSessionCalls(sessionId)).thenReturn(List.of());

        AgentObservationResponse response = service.getObservations(userId, sessionId);
        assertThat(response.steps()).isEmpty();
        assertThat(response.modelCalls()).isEmpty();
    }

    private AgentStep step(int number, UUID parent) {
        AgentStep step = new AgentStep();
        step.setId(UUID.randomUUID());
        step.setParentStepId(parent);
        step.setStepNo(number);
        step.setStepType("USER_INPUT");
        step.setStatus("COMPLETED");
        step.setStartedAt(OffsetDateTime.now());
        return step;
    }
}
