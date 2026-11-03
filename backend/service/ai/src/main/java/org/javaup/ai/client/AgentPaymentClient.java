package org.javaup.ai.client;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** Consumer-owned payment contract; no dependency on payment-service Java types. */
@FeignClient(name = "payment-service", contextId = "agentPaymentClient")
public interface AgentPaymentClient {

    @GetMapping("/internal/payment/entitlement")
    EntitlementResponse getEntitlement(@RequestHeader("X-User-Id") UUID userId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntitlementResponse(
            UUID userId,
            String planCode,
            String planName,
            int dailyModelCallLimit,
            Instant validUntil
    ) {
    }
}
