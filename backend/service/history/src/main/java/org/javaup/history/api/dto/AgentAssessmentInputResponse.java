package org.javaup.history.api.dto;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 内部提交凭据与一致性快照；AI 只冻结其中的稳定业务输入。 */
public record AgentAssessmentInputResponse(UUID assessmentId, UUID userId, int assessmentVersion,
        Instant submittedAt, String assessmentInputFingerprint, JsonNode assessment) {}
