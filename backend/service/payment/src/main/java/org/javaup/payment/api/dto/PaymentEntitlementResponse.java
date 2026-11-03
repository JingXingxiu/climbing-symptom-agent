package org.javaup.payment.api.dto;

import java.time.Instant;
import java.util.UUID;
import org.javaup.payment.entity.UserEntitlement;

public record PaymentEntitlementResponse(
        UUID userId,
        String planCode,
        String planName,
        int dailyModelCallLimit,
        Instant validUntil
) {

    public static PaymentEntitlementResponse fromEntity(
            UUID userId,
            UserEntitlement entitlement,
            Instant now
    ) {
        if (entitlement == null || !entitlement.getValidUntil().toInstant().isAfter(now)) {
            return new PaymentEntitlementResponse(userId, "FREE", "免费", 10, null);
        }
        return new PaymentEntitlementResponse(
                userId,
                entitlement.getPlanCode(),
                entitlement.getPlanName(),
                entitlement.getDailyModelCallLimit(),
                entitlement.getValidUntil().toInstant()
        );
    }
}
