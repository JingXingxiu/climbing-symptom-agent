package org.javaup.ai.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.JsonNode;

/** Consumer-owned DTO，不依赖 History 的 Entity 或 DTO Java 包。 */
@FeignClient(name = "history-service", contextId = "agentHistoryClient")
public interface AgentHistoryClient {

    @GetMapping("/internal/history/assessments/{assessmentId}/agent-input")
    InputResponse getInput(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable("assessmentId") UUID assessmentId
    );

    @PostMapping("/internal/history/assessments/{assessmentId}/agent-results")
    FinalizedResponse finalizeResult(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable("assessmentId") UUID assessmentId,
            @RequestBody FinalizeRequest request
    );

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InputResponse(
            UUID assessmentId,
            UUID userId,
            int assessmentVersion,
            Instant submittedAt,
            String assessmentInputFingerprint,
            JsonNode assessment
    ) {
    }

    record FinalizeRequest(
            UUID sessionId,
            UUID stepId,
            UUID checkpointId,
            int attemptNo,
            String recommendationLevel,
            String summary,
            List<String> reasons,
            List<String> suggestedNextActions,
            List<String> warningSigns,
            String safetyNotice,
            Instant finalizedAt,
            String workflowVersion,
            String generatedBy,
            String assessmentInputFingerprint
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinalizedResponse(
            UUID resultId,
            int revisionNo,
            Instant finalizedAt
    ) {
    }
}
