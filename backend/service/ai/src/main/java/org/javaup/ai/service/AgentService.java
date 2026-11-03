package org.javaup.ai.service;

import java.util.List;
import java.util.UUID;
import org.javaup.ai.api.dto.AgentFinalizationResponse;
import org.javaup.ai.api.dto.AgentOutcomeResponse;
import org.javaup.ai.api.dto.AgentSessionResponse;
import org.javaup.ai.api.dto.AgentStepSummary;
import org.javaup.ai.api.dto.AgentVersionRequest;
import org.javaup.ai.api.dto.AnswerAgentQuestionRequest;
import org.javaup.ai.api.dto.ChangeAgentModelRequest;
import org.javaup.ai.api.dto.CreateAgentSessionRequest;
import org.javaup.ai.api.dto.ReviseAgentAnswerRequest;

/** 有限步骤分析：数据库状态变更与远程模型调用分开提交。 */
public interface AgentService {

    AgentSessionResponse createAgentSession(
            UUID userId,
            UUID idempotencyKey,
            CreateAgentSessionRequest request
    );

    AgentSessionResponse getAgentSession(
            UUID userId,
            UUID sessionId
    );

    AgentSessionResponse getActiveSession(
            UUID userId,
            UUID assessmentId
    );

    AgentSessionResponse answerAgentQuestion(
            UUID userId,
            UUID sessionId,
            UUID idempotencyKey,
            AnswerAgentQuestionRequest request
    );

    AgentSessionResponse advanceSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    );

    AgentSessionResponse pauseSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    );

    AgentSessionResponse resumeSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    );

    AgentSessionResponse cancelSession(
            UUID userId,
            UUID sessionId,
            AgentVersionRequest request
    );

    AgentSessionResponse reviseAnswer(
            UUID userId,
            UUID sessionId,
            UUID idempotencyKey,
            ReviseAgentAnswerRequest request
    );

    AgentSessionResponse changeModel(
            UUID userId,
            UUID sessionId,
            ChangeAgentModelRequest request
    );

    List<AgentStepSummary> listSteps(
            UUID userId,
            UUID sessionId
    );

    AgentOutcomeResponse getAgentOutcome(
            UUID userId,
            UUID sessionId
    );

    AgentFinalizationResponse finalizeOutcome(
            UUID userId,
            UUID sessionId
    );
}
