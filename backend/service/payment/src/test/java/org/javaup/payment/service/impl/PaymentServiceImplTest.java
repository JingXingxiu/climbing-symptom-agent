package org.javaup.payment.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.javaup.payment.api.dto.CreatePaymentOrderRequest;
import org.javaup.payment.api.dto.PaymentEntitlementResponse;
import org.javaup.payment.api.dto.PaymentOrderResponse;
import org.javaup.payment.api.problem.ApiException;
import org.javaup.payment.config.PaymentProperties;
import org.javaup.payment.entity.PaymentOrder;
import org.javaup.payment.entity.PaymentPlan;
import org.javaup.payment.entity.UserEntitlement;
import org.javaup.payment.mapper.PaymentOrderMapper;
import org.javaup.payment.mapper.PaymentPlanMapper;
import org.javaup.payment.mapper.UserEntitlementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Local business rules and lock order; SQL binding is checked separately without a database. */
class PaymentServiceImplTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-02T08:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();

    private PaymentPlanMapper planMapper;
    private PaymentOrderMapper orderMapper;
    private UserEntitlementMapper entitlementMapper;
    private PaymentProperties properties;
    private MockEnvironment environment;
    private PaymentServiceImpl service;
    private PaymentPlan plan;
    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        planMapper = mock(PaymentPlanMapper.class);
        orderMapper = mock(PaymentOrderMapper.class);
        entitlementMapper = mock(UserEntitlementMapper.class);
        properties = new PaymentProperties();
        environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        service = new PaymentServiceImpl(planMapper, orderMapper, entitlementMapper, properties, environment, clock);
        plan = plusPlan();
        order = PaymentOrder.create(userId, idempotencyKey, plan, NOW);
    }

    @Test
    void createsAnOrderWithServerSideTermsAndThirtyMinuteExpiry() {
        when(planMapper.findActivePlan("PLUS")).thenReturn(plan);
        when(orderMapper.insertIfAbsent(any())).thenReturn(1);

        PaymentOrderResponse response = service.createOrder(userId, idempotencyKey, request("PLUS"));

        assertEquals(1990, response.priceCents());
        assertEquals("CNY", response.currency());
        assertEquals(30, response.durationDays());
        assertEquals(50, response.dailyModelCallLimit());
        assertEquals(NOW.plusMinutes(30).toInstant(), response.expiresAt());
        assertEquals("PENDING", response.status());
        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderMapper).insertIfAbsent(captor.capture());
        assertEquals(userId, captor.getValue().getUserId());
        assertEquals(idempotencyKey, captor.getValue().getIdempotencyKey());
        verifyNoInteractions(entitlementMapper);
    }

    @Test
    void sameKeyReplaysThePersistedSnapshotEvenIfThePlanIsNoLongerActive() {
        when(orderMapper.findByIdempotencyKey(userId, idempotencyKey)).thenReturn(order);

        PaymentOrderResponse response = service.createOrder(userId, idempotencyKey, request("PLUS"));

        assertEquals(order.getId(), response.orderId());
        verifyNoInteractions(planMapper, entitlementMapper);
        verify(orderMapper, never()).insertIfAbsent(any());
    }

    @Test
    void sameKeyForADifferentPlanConflictsBeforeLookingUpTheRequestedPlan() {
        when(orderMapper.findByIdempotencyKey(userId, idempotencyKey)).thenReturn(order);

        assertApiFailure(409, "PAYMENT_IDEMPOTENCY_CONFLICT",
                () -> service.createOrder(userId, idempotencyKey, request("OTHER")));

        verifyNoInteractions(planMapper, entitlementMapper);
    }

    @ParameterizedTest
    @CsvSource({"PLUS, 201", "OTHER, 409"})
    void aConcurrentInsertResolvesTheCommittedWinnerAndComparesItsPlan(String requestedPlan, int expectedStatus) {
        when(orderMapper.findByIdempotencyKey(userId, idempotencyKey)).thenReturn(null, order);
        when(planMapper.findActivePlan(requestedPlan)).thenReturn(plan);
        when(orderMapper.insertIfAbsent(any())).thenReturn(0);

        if (expectedStatus == 409) {
            assertApiFailure(409, "PAYMENT_IDEMPOTENCY_CONFLICT",
                    () -> service.createOrder(userId, idempotencyKey, request(requestedPlan)));
        } else {
            assertEquals(order.getId(), service.createOrder(userId, idempotencyKey, request(requestedPlan)).orderId());
        }
        InOrder sequence = inOrder(orderMapper);
        sequence.verify(orderMapper).findByIdempotencyKey(userId, idempotencyKey);
        sequence.verify(orderMapper).insertIfAbsent(any());
        sequence.verify(orderMapper).findByIdempotencyKey(userId, idempotencyKey);
    }

    @Test
    void unknownPlanDoesNotCreateAnOrder() {
        assertApiFailure(404, "PAYMENT_PLAN_NOT_FOUND",
                () -> service.createOrder(userId, idempotencyKey, request("MISSING")));
        verify(orderMapper, never()).insertIfAbsent(any());
    }

    @Test
    void confirmsTheFirstPaymentUnderOrderThenEntitlementLocksUsingOneLocalTransaction() throws Exception {
        enableMock();
        when(orderMapper.findOwnedForUpdate(userId, order.getId())).thenReturn(order);
        UserEntitlement entitlement = UserEntitlement.create(userId, order, NOW);
        when(entitlementMapper.findByUserIdForUpdate(userId)).thenReturn(entitlement);
        when(entitlementMapper.updateEntitlement(any())).thenReturn(1);
        when(orderMapper.markPaid(any())).thenReturn(1);

        PaymentOrderResponse response = service.mockPay(userId, order.getId());

        assertEquals("PAID", response.status());
        assertEquals(NOW.toInstant(), response.paidAt());
        assertEquals(NOW.plusDays(30), entitlement.getValidUntil());
        assertEquals(50, entitlement.getDailyModelCallLimit());
        InOrder sequence = inOrder(orderMapper, entitlementMapper);
        sequence.verify(orderMapper).findOwnedForUpdate(userId, order.getId());
        sequence.verify(entitlementMapper).insertIfAbsent(any());
        sequence.verify(entitlementMapper).findByUserIdForUpdate(userId);
        sequence.verify(entitlementMapper).updateEntitlement(entitlement);
        sequence.verify(orderMapper).markPaid(order);
        Transactional transaction = PaymentServiceImpl.class.getMethod("mockPay", UUID.class, UUID.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
    }

    @Test
    void replayingAPaidOrderDoesNotExtendEntitlementsAgainEvenAfterItsOriginalExpiry() {
        enableMock();
        order.setExpiresAt(NOW.minusMinutes(1));
        order.markPaid(NOW.minusDays(1));
        when(orderMapper.findOwnedForUpdate(userId, order.getId())).thenReturn(order);

        PaymentOrderResponse response = service.mockPay(userId, order.getId());

        assertEquals(order.getPaidAt().toInstant(), response.paidAt());
        verifyNoInteractions(entitlementMapper);
        verify(orderMapper, never()).markPaid(any());
    }

    @ParameterizedTest
    @CsvSource({"-1, 30", "0, 30", "10, 40"})
    void renewalAddsThirtyDaysToTheLaterOfNowAndCurrentValidity(int oldValidityDays, int expectedDays) {
        enableMock();
        when(orderMapper.findOwnedForUpdate(userId, order.getId())).thenReturn(order);
        UserEntitlement entitlement = UserEntitlement.create(userId, order, NOW.minusDays(10));
        entitlement.setValidUntil(NOW.plusDays(oldValidityDays));
        when(entitlementMapper.findByUserIdForUpdate(userId)).thenReturn(entitlement);
        when(entitlementMapper.updateEntitlement(any())).thenReturn(1);
        when(orderMapper.markPaid(any())).thenReturn(1);

        service.mockPay(userId, order.getId());

        assertEquals(NOW.plusDays(expectedDays), entitlement.getValidUntil());
    }

    @Test
    void payingDifferentOrdersForOneUserRenewsTheSameLockedEntitlement() {
        enableMock();
        PaymentOrder second = PaymentOrder.create(userId, UUID.randomUUID(), plan, NOW);
        when(orderMapper.findOwnedForUpdate(userId, order.getId())).thenReturn(order);
        when(orderMapper.findOwnedForUpdate(userId, second.getId())).thenReturn(second);
        UserEntitlement entitlement = UserEntitlement.create(userId, order, NOW);
        when(entitlementMapper.findByUserIdForUpdate(userId)).thenReturn(entitlement);
        when(entitlementMapper.updateEntitlement(any())).thenReturn(1);
        when(orderMapper.markPaid(any())).thenReturn(1);

        service.mockPay(userId, order.getId());
        service.mockPay(userId, second.getId());

        assertEquals(NOW.plusDays(60), entitlement.getValidUntil());
    }

    @Test
    void expiredPendingOrderConflictsWithoutWritingATransientExpiredStatus() {
        enableMock();
        order.setExpiresAt(NOW);
        when(orderMapper.findOwnedForUpdate(userId, order.getId())).thenReturn(order);

        assertApiFailure(409, "PAYMENT_ORDER_EXPIRED", () -> service.mockPay(userId, order.getId()));

        assertEquals("PENDING", order.getStatus());
        verifyNoInteractions(entitlementMapper);
        verify(orderMapper, never()).markPaid(any());
    }

    @Test
    void mockPaymentIsDisabledByDefaultEvenInDev() {
        assertApiFailure(403, "MOCK_PAYMENT_DISABLED", () -> service.mockPay(userId, order.getId()));
        verifyNoInteractions(orderMapper, entitlementMapper);
    }

    @ParameterizedTest
    @CsvSource({"prod", "production", "default", "test"})
    void aTrueFlagDoesNotEnableMockOutsideDev(String activeProfile) {
        properties.setMockEnabled(true);
        environment.setActiveProfiles(activeProfile);

        assertApiFailure(403, "MOCK_PAYMENT_DISABLED", () -> service.mockPay(userId, order.getId()));

        verifyNoInteractions(orderMapper, entitlementMapper);
    }

    @Test
    void mixedDevAndProductionProfilesRemainDisabled() {
        properties.setMockEnabled(true);
        environment.setActiveProfiles("dev", "prod");
        assertApiFailure(403, "MOCK_PAYMENT_DISABLED", () -> service.mockPay(userId, order.getId()));
        environment.setActiveProfiles("dev", "production");
        assertApiFailure(403, "MOCK_PAYMENT_DISABLED", () -> service.mockPay(userId, order.getId()));
        verifyNoInteractions(orderMapper, entitlementMapper);
    }

    @Test
    void missingOrOtherUsersOrdersReturnTheSameNotFound() {
        enableMock();
        assertApiFailure(404, "PAYMENT_ORDER_NOT_FOUND", () -> service.getOrder(userId, order.getId()));
        assertApiFailure(404, "PAYMENT_ORDER_NOT_FOUND", () -> service.mockPay(userId, order.getId()));
        verify(orderMapper).findOwned(userId, order.getId());
        verify(orderMapper).findOwnedForUpdate(userId, order.getId());
        verifyNoInteractions(entitlementMapper);
    }

    @Test
    void entitlementWriteFailureStopsBeforeMarkingTheOrderPaid() {
        enableMock();
        when(orderMapper.findOwnedForUpdate(userId, order.getId())).thenReturn(order);
        when(entitlementMapper.findByUserIdForUpdate(userId))
                .thenReturn(UserEntitlement.create(userId, order, NOW));
        doThrow(new IllegalStateException("write failed")).when(entitlementMapper).updateEntitlement(any());

        assertThrows(IllegalStateException.class, () -> service.mockPay(userId, order.getId()));

        verify(orderMapper, never()).markPaid(any());
    }

    @Test
    void missingAndExpiredEntitlementsResolveToFreeAtTheExactExpiryBoundary() {
        assertEquals(new PaymentEntitlementResponse(userId, "FREE", "免费", 10, null),
                service.getEntitlement(userId));
        UserEntitlement entitlement = UserEntitlement.create(userId, order, NOW);
        when(entitlementMapper.findByUserId(userId)).thenReturn(entitlement);
        assertEquals("FREE", service.getEntitlement(userId).planCode());
        entitlement.setValidUntil(NOW.minusNanos(1));
        assertEquals("FREE", service.getEntitlement(userId).planCode());
        entitlement.setValidUntil(NOW.plusNanos(1));
        assertEquals("PLUS", service.getEntitlement(userId).planCode());
        assertEquals(50, service.getEntitlement(userId).dailyModelCallLimit());
    }

    @Test
    void listUsesTheCurrentUserAndValidatedLimitAndMapsOnlyPublicSnapshots() {
        when(orderMapper.findRecent(userId, 20)).thenReturn(List.of(order));
        assertEquals(List.of(PaymentOrderResponse.fromEntity(order)), service.listOrders(userId, 20));
        assertApiFailure(400, "INVALID_ORDER_LIMIT", () -> service.listOrders(userId, 0));
        assertApiFailure(400, "INVALID_ORDER_LIMIT", () -> service.listOrders(userId, 51));
    }

    @Test
    void creationUsesReadCommittedSoTheLosingInsertCanReadTheCommittedWinner() throws Exception {
        Transactional transaction = PaymentServiceImpl.class.getMethod("createOrder", UUID.class, UUID.class,
                CreatePaymentOrderRequest.class).getAnnotation(Transactional.class);
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
    }

    private void enableMock() {
        properties.setMockEnabled(true);
    }

    private static CreatePaymentOrderRequest request(String planCode) {
        return new CreatePaymentOrderRequest(planCode);
    }

    private static PaymentPlan plusPlan() {
        PaymentPlan plan = new PaymentPlan();
        plan.setPlanCode("PLUS");
        plan.setName("PLUS会员");
        plan.setPriceCents(1990);
        plan.setCurrency("CNY");
        plan.setDurationDays(30);
        plan.setDailyModelCallLimit(50);
        plan.setActive(true);
        return plan;
    }

    private static void assertApiFailure(int status, String code, Runnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(status, exception.status().value());
        assertEquals(code, exception.code());
    }
}
