package org.javaup.ai.api;

import java.util.UUID;

import org.javaup.ai.api.dto.AgentObservationResponse;
import org.javaup.ai.service.AgentObservationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/ai/v1/agent-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentObservationController {

    private final AgentObservationService service;

    public AgentObservationController(AgentObservationService service) {
        this.service = service;
    }

    @GetMapping("/{sessionId}/observations")
    public AgentObservationResponse getObservations(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID sessionId
    ) {
        return service.getObservations(userId, sessionId);
    }
}
