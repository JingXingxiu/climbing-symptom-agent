package org.javaup.ai.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AgentQuotaResponse(
        UUID userId,
        LocalDate usageDate,
        String zoneId,
        String planCode,
        int usedModelCalls,
        int dailyModelCallLimit,
        int remainingModelCalls,
        Instant entitlementValidUntil
) {
}
