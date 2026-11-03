package org.javaup.ai.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.javaup.ai.api.dto.AgentOutcomeResponse;
import org.javaup.ai.model.AgentModelRequest;
import tools.jackson.databind.JsonNode;

/** AI 持有输入快照，History 持有最终业务结果；接口调用不放进 AI 数据库事务。 */
public interface AgentAssessmentBridge {

    Snapshot loadSnapshot(
            UUID userId,
            UUID assessmentId
    );

    List<AgentModelRequest.Picture> resolvePictures(
            UUID userId,
            JsonNode inputSnapshot
    );

    FinalizedResult finalizeResult(
            UUID userId,
            UUID sessionId,
            UUID outputStepId,
            UUID checkpointId,
            AgentOutcomeResponse outcome,
            String workflowVersion,
            int attemptNo,
            String generatedBy,
            String assessmentInputFingerprint
    );

    record Snapshot(
            int assessmentVersion,
            JsonNode inputSnapshot
    ) {}

    record FinalizedResult(
            UUID resultId,
            int revisionNo,
            Instant finalizedAt
    ) {}
}
