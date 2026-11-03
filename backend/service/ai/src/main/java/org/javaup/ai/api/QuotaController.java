package org.javaup.ai.api;

import java.util.UUID;

import org.javaup.ai.api.dto.AgentQuotaResponse;
import org.javaup.ai.service.AgentQuotaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/ai/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class QuotaController {
    private final AgentQuotaService quota;

    public QuotaController(AgentQuotaService quota) {
        this.quota = quota;
    }

    @GetMapping("/quota")
    public AgentQuotaResponse getQuota(@RequestHeader("X-User-Id") UUID userId) {
        return quota.getQuota(userId);
    }
}
