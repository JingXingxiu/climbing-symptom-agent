package org.javaup.history.service;

import java.util.UUID;
import org.javaup.history.api.dto.AgentAssessmentInputResponse;
import org.javaup.history.api.dto.FinalizeAgentResultRequest;
import org.javaup.history.api.dto.FinalizedAgentResultResponse;

/** History 自己完成结果事务，不直接读写 AI 库。 */
public interface AgentResultService {
    AgentAssessmentInputResponse getAgentInput(UUID userId, UUID assessmentId);
    FinalizedAgentResultResponse finalizeResult(UUID userId, UUID assessmentId, FinalizeAgentResultRequest request);
}
