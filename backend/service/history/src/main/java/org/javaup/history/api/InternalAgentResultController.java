package org.javaup.history.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.javaup.history.api.dto.AgentAssessmentInputResponse;
import org.javaup.history.api.dto.FinalizeAgentResultRequest;
import org.javaup.history.api.dto.FinalizedAgentResultResponse;
import org.javaup.history.service.AgentResultService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅服务间调用；Gateway 不映射 /internal/**，部署时服务端口只暴露在可信内网。 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(value = "/internal/history/assessments", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalAgentResultController {
    private final AgentResultService agentResultService;

    @GetMapping("/{assessmentId}/agent-input")
    public AgentAssessmentInputResponse getAgentInput(
            @RequestHeader("X-User-Id") @NotNull UUID userId, @PathVariable UUID assessmentId) {
        return agentResultService.getAgentInput(userId, assessmentId);
    }

    @PostMapping(value = "/{assessmentId}/agent-results", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FinalizedAgentResultResponse finalizeResult(
            @RequestHeader("X-User-Id") @NotNull UUID userId, @PathVariable UUID assessmentId,
            @Valid @RequestBody FinalizeAgentResultRequest request) {
        return agentResultService.finalizeResult(userId, assessmentId, request);
    }
}
