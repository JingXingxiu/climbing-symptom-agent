package org.javaup.payment.service.impl;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.javaup.payment.api.dto.CreatePaymentOrderRequest;
import org.javaup.payment.api.dto.PaymentEntitlementResponse;
import org.javaup.payment.api.dto.PaymentOrderResponse;
import org.javaup.payment.api.dto.PaymentPlanResponse;
import org.javaup.payment.api.problem.ApiException;
import org.javaup.payment.config.PaymentProperties;
import org.javaup.payment.entity.PaymentOrder;
import org.javaup.payment.entity.PaymentPlan;
import org.javaup.payment.entity.UserEntitlement;
import org.javaup.payment.mapper.PaymentOrderMapper;
import org.javaup.payment.mapper.PaymentPlanMapper;
import org.javaup.payment.mapper.UserEntitlementMapper;
import org.javaup.payment.service.PaymentService;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentPlanMapper planMapper;
    private final PaymentOrderMapper orderMapper;
    private final UserEntitlementMapper entitlementMapper;
    private final PaymentProperties properties;
    private final Environment environment;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentPlanResponse> listPlans() {
        return planMapper.findActivePlans().stream()
                .map(PaymentPlanResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentOrderResponse createOrder(
            UUID userId,
            UUID idempotencyKey,
            CreatePaymentOrderRequest request
    ) {
        PaymentOrder existing = orderMapper.findByIdempotencyKey(userId, idempotencyKey);
        if (existing != null) {
            return replayOrder(existing, request.planCode());
        }
        PaymentPlan plan = planMapper.findActivePlan(request.planCode());
        if (plan == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PAYMENT_PLAN_NOT_FOUND",
                    "Purchasable plan does not exist."
            );
        }
        PaymentOrder order = PaymentOrder.create(userId, idempotencyKey, plan, OffsetDateTime.now(clock));
        if (orderMapper.insertIfAbsent(order) == 1) {
            return PaymentOrderResponse.fromEntity(order);
        }
        // DO NOTHING may wait for a competing insertion; READ COMMITTED sees its committed winner here.
        existing = orderMapper.findByIdempotencyKey(userId, idempotencyKey);
        if (existing == null) {
            throw new IllegalStateException("Idempotent order insert completed without a persisted order.");
        }
        return replayOrder(existing, request.planCode());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentOrderResponse> listOrders(UUID userId, int limit) {
        if (limit < 1 || limit > 50) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ORDER_LIMIT",
                    "Limit must be between 1 and 50."
            );
        }
        return orderMapper.findRecent(userId, limit).stream()
                .map(PaymentOrderResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentOrderResponse getOrder(UUID userId, UUID orderId) {
        return PaymentOrderResponse.fromEntity(requireOrder(orderMapper.findOwned(userId, orderId)));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentOrderResponse mockPay(UUID userId, UUID orderId) {
        requireMockPaymentEnabled();
        PaymentOrder order = requireOrder(orderMapper.findOwnedForUpdate(userId, orderId));
        if (PaymentOrder.PAID.equals(order.getStatus())) {
            return PaymentOrderResponse.fromEntity(order);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        requireUnexpired(order, now);

        // A unique user row serializes different orders for the same user, including their first purchase.
        entitlementMapper.insertIfAbsent(UserEntitlement.create(userId, order, now));
        UserEntitlement entitlement = entitlementMapper.findByUserIdForUpdate(userId);
        if (entitlement == null) {
            throw new IllegalStateException("Entitlement row is missing after insert-if-absent.");
        }
        now = OffsetDateTime.now(clock);
        requireUnexpired(order, now);
        entitlement.renew(order, now);
        if (entitlementMapper.updateEntitlement(entitlement) != 1) {
            throw new IllegalStateException("Failed to persist the locked entitlement.");
        }
        order.markPaid(now);
        if (orderMapper.markPaid(order) != 1) {
            throw new IllegalStateException("Failed to persist the locked payment order.");
        }
        return PaymentOrderResponse.fromEntity(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentEntitlementResponse getEntitlement(UUID userId) {
        UserEntitlement entitlement = entitlementMapper.findByUserId(userId);
        return PaymentEntitlementResponse.fromEntity(userId, entitlement, clock.instant());
    }

    private PaymentOrderResponse replayOrder(PaymentOrder order, String requestedPlanCode) {
        if (!order.getPlanCode().equals(requestedPlanCode)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for another plan."
            );
        }
        return PaymentOrderResponse.fromEntity(order);
    }

    private PaymentOrder requireOrder(PaymentOrder order) {
        if (order == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PAYMENT_ORDER_NOT_FOUND",
                    "Payment order does not exist."
            );
        }
        return order;
    }

    private void requireUnexpired(PaymentOrder order, OffsetDateTime now) {
        if (!PaymentOrder.PENDING.equals(order.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_ORDER_STATE_CONFLICT",
                    "Payment order is not pending."
            );
        }
        if (!order.getExpiresAt().isAfter(now)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_ORDER_EXPIRED",
                    "Payment order has expired."
            );
        }
    }

    private void requireMockPaymentEnabled() {
        if (!properties.isMockEnabled() || !environment.matchesProfiles("dev & !prod & !production")) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "MOCK_PAYMENT_DISABLED",
                    "Mock payment requires an explicitly enabled development profile."
            );
        }
    }
}
