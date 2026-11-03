package org.javaup.ai.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.javaup.ai.api.dto.AgentFinalizationResponse;
import org.javaup.ai.api.dto.AgentOutcomeResponse;
import org.javaup.ai.api.dto.AgentSessionResponse;
import org.javaup.ai.api.dto.AgentStepSummary;
import org.javaup.ai.api.dto.AgentVersionRequest;
import org.javaup.ai.api.dto.AnswerAgentQuestionRequest;
import org.javaup.ai.api.dto.ChangeAgentModelRequest;
import org.javaup.ai.api.dto.CreateAgentSessionRequest;
import org.javaup.ai.api.dto.ReviseAgentAnswerRequest;
import org.javaup.ai.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller只负责传入用户身份、并发版本和请求体，业务在AgentService。 */
@Validated
@RestController
@RequestMapping(value = "/api/ai/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class AIController {

    private final AgentService service;

    public AIController(AgentService service) {
        this.service = service;
    }

    @PostMapping("/agent-sessions")
    public ResponseEntity<AgentSessionResponse> createAgentSession(
            @RequestHeader("X-User-Id") @NotNull UUID userId,
            @RequestHeader("X-Idempotency-Key") @NotNull UUID key,
            @Valid @RequestBody CreateAgentSessionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createAgentSession(userId, key, request));
    }

    @GetMapping("/agent-sessions/{sessionId}")
    public AgentSessionResponse getAgentSession(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId
    ) {
        return service.getAgentSession(userId, sessionId);
    }

    @GetMapping("/agent-sessions/active")
    public AgentSessionResponse getActiveSession(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam UUID assessmentId
    ) {
        return service.getActiveSession(userId, assessmentId);
    }

    @PostMapping("/agent-sessions/{sessionId}/responses")
    public AgentSessionResponse answerAgentQuestion(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @RequestHeader("X-Idempotency-Key") UUID key,
            @Valid @RequestBody AnswerAgentQuestionRequest request
    ) {
        return service.answerAgentQuestion(userId, sessionId, key, request);
    }

    @PostMapping("/agent-sessions/{sessionId}/advance")
    public AgentSessionResponse advance(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody AgentVersionRequest request
    ) {
        return service.advanceSession(userId, sessionId, request);
    }

    @PostMapping("/agent-sessions/{sessionId}/pause")
    public AgentSessionResponse pause(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody AgentVersionRequest request
    ) {
        return service.pauseSession(userId, sessionId, request);
    }

    @PostMapping("/agent-sessions/{sessionId}/resume")
    public AgentSessionResponse resume(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody AgentVersionRequest request
    ) {
        return service.resumeSession(userId, sessionId, request);
    }

    @PostMapping("/agent-sessions/{sessionId}/cancel")
    public AgentSessionResponse cancel(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody AgentVersionRequest request
    ) {
        return service.cancelSession(userId, sessionId, request);
    }

    @PostMapping("/agent-sessions/{sessionId}/revise-answer")
    public AgentSessionResponse reviseAnswer(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @RequestHeader("X-Idempotency-Key") UUID key,
            @Valid @RequestBody ReviseAgentAnswerRequest request
    ) {
        return service.reviseAnswer(userId, sessionId, key, request);
    }

    @PostMapping("/agent-sessions/{sessionId}/model")
    public AgentSessionResponse changeModel(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody ChangeAgentModelRequest request
    ) {
        return service.changeModel(userId, sessionId, request);
    }

    @GetMapping("/agent-sessions/{sessionId}/steps")
    public List<AgentStepSummary> listSteps(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId
    ) {
        return service.listSteps(userId, sessionId);
    }

    @GetMapping("/agent-sessions/{sessionId}/outcome")
    public AgentOutcomeResponse getAgentOutcome(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId
    ) {
        return service.getAgentOutcome(userId, sessionId);
    }

    @PostMapping("/agent-sessions/{sessionId}/finalize")
    public AgentFinalizationResponse finalizeOutcome(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId
    ) {
        return service.finalizeOutcome(userId, sessionId);
    }
}
