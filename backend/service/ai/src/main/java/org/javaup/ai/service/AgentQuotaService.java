package org.javaup.ai.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentQuotaResponse;
import org.javaup.ai.api.problem.AIErrorCode;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.client.AgentPaymentClient.EntitlementResponse;
import org.javaup.ai.client.PaymentEntitlementGuard;
import org.javaup.ai.mapper.UserDailyUsageMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentQuotaService {
    private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Shanghai");

    private final PaymentEntitlementGuard payment;
    private final UserDailyUsageMapper usage;
    private final Clock clock;

    public AgentQuotaService(PaymentEntitlementGuard payment, UserDailyUsageMapper usage, Clock clock) {
        this.payment = payment;
        this.usage = usage;
        this.clock = clock;
    }

    /** Payment is queried before opening any local execution transaction. */
    @Transactional(propagation = Propagation.NEVER)
    public EntitlementResponse getEntitlement(UUID userId) {
        if (userId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, AIErrorCode.INVALID_SESSION_ID, "User is required");
        }

        EntitlementResponse entitlement = payment.getEntitlement(userId);

        if (entitlement == null
                || !userId.equals(entitlement.userId())
                || entitlement.planCode() == null
                || entitlement.planCode().isBlank()
                || entitlement.planName() == null
                || entitlement.planName().isBlank()
                || entitlement.dailyModelCallLimit() <= 0
                || entitlement.validUntil() != null
                        && !entitlement.validUntil().isAfter(clock.instant())) {
            throw unavailable();
        }
        return entitlement;
    }

    /** Called inside the same short transaction as the session and ModelCall writes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID userId, EntitlementResponse entitlement) {
        int affected = usage.reserve(
                userId,
                usageDate(),
                entitlement.dailyModelCallLimit(),
                OffsetDateTime.now(clock));
        if (affected == 0) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    AIErrorCode.AI_DAILY_QUOTA_EXCEEDED,
                    "Daily AI execution quota is exhausted");
        }
        if (affected != 1) {
            throw new IllegalStateException("Daily AI usage reservation affected an unexpected row count");
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public AgentQuotaResponse getQuota(UUID userId) {
        EntitlementResponse entitlement = getEntitlement(userId);
        LocalDate date = usageDate();
        Integer stored = usage.selectUsedModelCalls(userId, date);
        int used = stored == null ? 0 : stored;
        int limit = entitlement.dailyModelCallLimit();
        return new AgentQuotaResponse(
                userId,
                date,
                USAGE_ZONE.getId(),
                entitlement.planCode(),
                used,
                limit,
                Math.max(0, limit - used),
                entitlement.validUntil());
    }

    private LocalDate usageDate() {
        return LocalDate.now(clock.withZone(USAGE_ZONE));
    }

    private ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                AIErrorCode.PAYMENT_ENTITLEMENT_UNAVAILABLE,
                "Current AI entitlement is unavailable; retry later");
    }
}
