package org.javaup.ai.api.dto;

import java.time.Instant;
import java.util.UUID;

/** History 已接受并持久化的结果，不等于仅在 AI 生成了候选。 */
public record AgentFinalizationResponse(
        UUID assessmentId,
        UUID sessionId,
        UUID resultId,
        int revisionNo,
        Instant finalizedAt
) {
}
