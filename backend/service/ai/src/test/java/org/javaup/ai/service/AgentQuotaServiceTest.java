package org.javaup.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.javaup.ai.api.dto.AgentQuotaResponse;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.client.AgentPaymentClient;
import org.javaup.ai.client.AgentPaymentClient.EntitlementResponse;
import org.javaup.ai.client.PaymentEntitlementGuard;
import org.javaup.ai.config.PaymentEntitlementBreakerProperties;
import org.javaup.ai.mapper.UserDailyUsageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class AgentQuotaServiceTest {
    private static final Instant TIME = Instant.parse("2026-10-27T16:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);

    private final UUID userId = UUID.randomUUID();
    private final AgentPaymentClient payment = mock(AgentPaymentClient.class);
    private final UserDailyUsageMapper usage = mock(UserDailyUsageMapper.class);
    private final AgentQuotaService service =
            new AgentQuotaService(
                    new PaymentEntitlementGuard(payment, new PaymentEntitlementBreakerProperties()),
                    usage,
                    Clock.fixed(TIME, ZoneOffset.UTC));

    @Test
    void quotaReadsPaymentBeforeLocalUsageAndNeverReserves() {
        when(payment.getEntitlement(userId)).thenReturn(entitlement("FREE", 10, null));
        when(usage.selectUsedModelCalls(userId, DATE)).thenReturn(4);

        AgentQuotaResponse result = service.getQuota(userId);

        assertEquals(userId, result.userId());
        assertEquals(DATE, result.usageDate());
        assertEquals("Asia/Shanghai", result.zoneId());
        assertEquals("FREE", result.planCode());
        assertEquals(4, result.usedModelCalls());
        assertEquals(10, result.dailyModelCallLimit());
        assertEquals(6, result.remainingModelCalls());
        assertNull(result.entitlementValidUntil());
        var order = inOrder(payment, usage);
        order.verify(payment).getEntitlement(userId);
        order.verify(usage).selectUsedModelCalls(userId, DATE);
        verify(usage, never()).reserve(any(), any(), anyInt(), any());
    }

    @Test
    void newDateReadsZeroWithoutCreatingOrResettingUsage() {
        when(payment.getEntitlement(userId)).thenReturn(entitlement("FREE", 10, null));

        AgentQuotaResponse result = service.getQuota(userId);

        assertEquals(0, result.usedModelCalls());
        assertEquals(10, result.remainingModelCalls());
        verify(usage, never()).reserve(any(), any(), anyInt(), any());
    }

    @Test
    void currentPlanChangesDoNotResetUsageAndExpiredPlanCanExposeZeroRemaining() {
        Instant expires = TIME.plusSeconds(30L * 24 * 60 * 60);
        when(payment.getEntitlement(userId))
                .thenReturn(entitlement("PLUS", 50, expires), entitlement("FREE", 10, null));
        when(usage.selectUsedModelCalls(userId, DATE)).thenReturn(20);

        AgentQuotaResponse plus = service.getQuota(userId);
        AgentQuotaResponse free = service.getQuota(userId);

        assertEquals(20, plus.usedModelCalls());
        assertEquals(30, plus.remainingModelCalls());
        assertEquals(expires, plus.entitlementValidUntil());
        assertEquals(20, free.usedModelCalls());
        assertEquals(0, free.remainingModelCalls());
        verify(usage, never()).reserve(any(), any(), anyInt(), any());
    }

    @Test
    void paymentFailureNeverFallsBackOrReadsLocalUsage() {
        when(payment.getEntitlement(userId)).thenThrow(new IllegalStateException("offline"));

        ApiException failure = assertThrows(ApiException.class, () -> service.getQuota(userId));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        assertEquals("PAYMENT_ENTITLEMENT_UNAVAILABLE", failure.code());
        verifyNoInteractions(usage);
    }

    @Test
    void invalidPaymentResponsesFailClosedBeforeUsageRead() {
        List<EntitlementResponse> invalid = Arrays.asList(
                null,
                new EntitlementResponse(UUID.randomUUID(), "FREE", "免费", 10, null),
                new EntitlementResponse(userId, null, "免费", 10, null),
                new EntitlementResponse(userId, " ", "免费", 10, null),
                new EntitlementResponse(userId, "FREE", null, 10, null),
                entitlement("FREE", 0, null),
                entitlement("FREE", -1, null),
                entitlement("PLUS", 50, TIME),
                entitlement("PLUS", 50, TIME.minusSeconds(1)));
        for (EntitlementResponse response : invalid) {
            when(payment.getEntitlement(userId)).thenReturn(response);
            ApiException failure = assertThrows(ApiException.class, () -> service.getQuota(userId));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        }
        verifyNoInteractions(usage);
    }

    @Test
    void reservationUsesBusinessDateAndCurrentLimitWithoutARead() {
        when(usage.reserve(eq(userId), eq(DATE), eq(50), any())).thenReturn(1);

        service.reserve(userId, entitlement("PLUS", 50, TIME.plusSeconds(100)));

        verify(usage).reserve(eq(userId), eq(DATE), eq(50), any());
        verify(usage, never()).selectUsedModelCalls(any(), any());
        verifyNoInteractions(payment);
    }

    @Test
    void zeroAffectedRowsMeansDailyQuotaExceeded() {
        ApiException failure = assertThrows(
                ApiException.class, () -> service.reserve(userId, entitlement("FREE", 10, null)));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.status());
        assertEquals("AI_DAILY_QUOTA_EXCEEDED", failure.code());
    }

    @Test
    void transactionAdviceRejectsPaymentInTransactionAndReservationWithoutOne() {
        PlatformTransactionManager manager = transactionManager();
        AgentQuotaService advised = advised(manager);

        assertThrows(IllegalTransactionStateException.class,
                () -> advised.reserve(userId, entitlement("FREE", 10, null)));
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThrows(IllegalTransactionStateException.class, () -> advised.getEntitlement(userId));
            assertThrows(IllegalTransactionStateException.class, () -> advised.getQuota(userId));
        });
        verifyNoInteractions(payment, usage);
    }

    @Test
    void reservationJoinsLocalTransactionAndLaterWriteFailureRollsItBack() {
        RecordingTransactions manager = new RecordingTransactions();
        AgentQuotaService advised = advised(manager);
        when(usage.reserve(eq(userId), eq(DATE), eq(10), any())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });

        assertThrows(IllegalStateException.class, () ->
                new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    advised.reserve(userId, entitlement("FREE", 10, null));
                    throw new IllegalStateException("Later Session/ModelCall write failed");
                }));

        assertEquals(1, manager.rollbacks);
        assertEquals(0, manager.commits);
        verify(usage).reserve(eq(userId), eq(DATE), eq(10), any());
        verifyNoInteractions(payment);
    }

    @Test
    void quotaJsonUsesIsoBusinessDateAndNullableExpiry() {
        when(payment.getEntitlement(userId)).thenReturn(entitlement("FREE", 10, null));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

        var result = mapper.valueToTree(service.getQuota(userId));

        assertEquals("2026-10-28", result.path("usageDate").asText());
        assertEquals("Asia/Shanghai", result.path("zoneId").asText());
        assertTrue(result.has("entitlementValidUntil"));
        assertTrue(result.path("entitlementValidUntil").isNull());
    }

    private EntitlementResponse entitlement(String plan, int limit, Instant expires) {
        return new EntitlementResponse(userId, plan, plan, limit, expires);
    }

    private PlatformTransactionManager transactionManager() {
        return new RecordingTransactions();
    }

    private AgentQuotaService advised(PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (AgentQuotaService) factory.getProxy();
    }

    private static class RecordingTransactions extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
