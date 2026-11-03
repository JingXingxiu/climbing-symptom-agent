package org.javaup.payment.api.dto;

import java.time.Instant;
import java.util.UUID;
import org.javaup.payment.entity.PaymentOrder;

public record PaymentOrderResponse(
        UUID orderId,
        String planCode,
        String planName,
        int priceCents,
        String currency,
        int durationDays,
        int dailyModelCallLimit,
        String status,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static PaymentOrderResponse fromEntity(PaymentOrder order) {
        return new PaymentOrderResponse(
                order.getId(),
                order.getPlanCode(),
                order.getPlanName(),
                order.getPriceCents(),
                order.getCurrency(),
                order.getDurationDays(),
                order.getDailyModelCallLimit(),
                order.getStatus(),
                order.getExpiresAt().toInstant(),
                order.getPaidAt() == null ? null : order.getPaidAt().toInstant(),
                order.getCreatedAt().toInstant(),
                order.getUpdatedAt().toInstant()
        );
    }
}
