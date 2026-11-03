package org.javaup.payment.api.dto;

import org.javaup.payment.entity.PaymentPlan;

public record PaymentPlanResponse(
        String planCode,
        String name,
        int priceCents,
        String currency,
        int durationDays,
        int dailyModelCallLimit
) {

    public static PaymentPlanResponse fromEntity(PaymentPlan plan) {
        return new PaymentPlanResponse(
                plan.getPlanCode(),
                plan.getName(),
                plan.getPriceCents(),
                plan.getCurrency(),
                plan.getDurationDays(),
                plan.getDailyModelCallLimit()
        );
    }
}
