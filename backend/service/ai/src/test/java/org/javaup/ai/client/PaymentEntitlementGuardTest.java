package org.javaup.ai.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.util.TimeUtil;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.client.AgentPaymentClient.EntitlementResponse;
import org.javaup.ai.config.PaymentEntitlementBreakerConfiguration;
import org.javaup.ai.config.PaymentEntitlementBreakerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;

/** Real Sentinel entries/rules/circuit breaker; only the clock and Payment are replaced. */
class PaymentEntitlementGuardTest {

    private final UUID userId = UUID.randomUUID();
    private final EntitlementResponse entitlement =
            new EntitlementResponse(userId, "PLUS", "PLUS", 50, null);
    private final AgentPaymentClient payment = mock(AgentPaymentClient.class);
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final PaymentEntitlementBreakerProperties properties = new PaymentEntitlementBreakerProperties();
    private MockedStatic<TimeUtil> time;

    @BeforeEach
    void setUp() {
        DegradeRuleManager.setRulesForResource(PaymentEntitlementGuard.RESOURCE, Set.of());
        time = mockStatic(TimeUtil.class);
        time.when(TimeUtil::currentTimeMillis).thenAnswer(invocation -> now.get());
    }

    @AfterEach
    void tearDown() {
        DegradeRuleManager.setRulesForResource(PaymentEntitlementGuard.RESOURCE, Set.of());
        time.close();
    }

    @Test
    void successReturnsTheActualEntitlementWithoutRetrying() {
        when(payment.getEntitlement(userId)).thenReturn(entitlement);

        assertSame(entitlement, guard().getEntitlement(userId));

        verify(payment).getEntitlement(userId);
    }

    @Test
    void fiveServerFailuresOpenAndOneSuccessfulHalfOpenProbeRecovers() {
        PaymentEntitlementGuard guard = guard();
        when(payment.getEntitlement(userId)).thenThrow(httpFailure(503));

        for (int i = 0; i < 5; i++) {
            assertUnavailable(guard);
        }
        ApiException blocked = assertUnavailable(guard);
        assertTrue(blocked.getMessage().contains("temporarily blocked"));
        verify(payment, times(5)).getEntitlement(userId);

        now.addAndGet(10_001);
        doReturn(entitlement).when(payment).getEntitlement(userId);

        assertSame(entitlement, guard.getEntitlement(userId));
        assertSame(entitlement, guard.getEntitlement(userId));
        verify(payment, times(7)).getEntitlement(userId);
    }

    @Test
    void failedHalfOpenProbeReopensWithoutCallingPaymentAgain() {
        PaymentEntitlementGuard guard = guard();
        when(payment.getEntitlement(userId)).thenThrow(httpFailure(500));
        for (int i = 0; i < 5; i++) {
            assertUnavailable(guard);
        }

        now.addAndGet(10_001);
        assertUnavailable(guard);
        assertTrue(assertUnavailable(guard).getMessage().contains("temporarily blocked"));

        verify(payment, times(6)).getEntitlement(userId);
    }

    @Test
    void transportFailuresAlsoOpenTheCircuit() {
        PaymentEntitlementGuard guard = guard();
        when(payment.getEntitlement(userId))
                .thenThrow(new UncheckedIOException(new ConnectException("offline")));
        for (int i = 0; i < 5; i++) {
            assertUnavailable(guard);
        }

        assertTrue(assertUnavailable(guard).getMessage().contains("temporarily blocked"));

        verify(payment, times(5)).getEntitlement(userId);
    }

    @Test
    void feignTransportFailuresWithoutAnHttpStatusOpenTheCircuit() {
        PaymentEntitlementGuard guard = guard();
        when(payment.getEntitlement(userId)).thenThrow(httpFailure(-1));
        for (int i = 0; i < 5; i++) {
            assertUnavailable(guard);
        }

        assertTrue(assertUnavailable(guard).getMessage().contains("temporarily blocked"));
        verify(payment, times(5)).getEntitlement(userId);
    }

    @Test
    void configuredRatioIsStrictlyExceededBeforeOpening() {
        properties.setMinimumRequestAmount(4);
        PaymentEntitlementGuard guard = guard();
        doReturn(entitlement).when(payment).getEntitlement(userId);
        guard.getEntitlement(userId);
        guard.getEntitlement(userId);
        when(payment.getEntitlement(userId)).thenThrow(httpFailure(503));
        assertUnavailable(guard);
        assertUnavailable(guard);
        // Two failures out of four equal 0.5; the fifth call is still allowed.
        assertFalse(assertUnavailable(guard).getMessage().contains("temporarily blocked"));

        assertTrue(assertUnavailable(guard).getMessage().contains("temporarily blocked"));
        verify(payment, times(5)).getEntitlement(userId);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 429})
    void businessClientErrorsFailClosedWithoutOpeningTheCircuit(int status) {
        PaymentEntitlementGuard guard = guard();
        when(payment.getEntitlement(userId)).thenThrow(httpFailure(status));
        for (int i = 0; i < 10; i++) {
            assertFalse(assertUnavailable(guard).getMessage().contains("temporarily blocked"));
        }
        doReturn(entitlement).when(payment).getEntitlement(userId);

        assertSame(entitlement, guard.getEntitlement(userId));

        verify(payment, times(11)).getEntitlement(userId);
    }

    @Test
    void unknownLocalExceptionsAreNotCountedAsTransportFailures() {
        PaymentEntitlementGuard guard = guard();
        when(payment.getEntitlement(userId)).thenThrow(new IllegalStateException("local failure"));
        for (int i = 0; i < 10; i++) {
            assertFalse(assertUnavailable(guard).getMessage().contains("temporarily blocked"));
        }
        verify(payment, times(10)).getEntitlement(userId);
    }

    @Test
    void disabledProtectionStillFailsClosedButAlwaysCallsPayment() {
        properties.setEnabled(false);
        PaymentEntitlementGuard guard = guard();
        assertTrue(DegradeRuleManager.getRulesOfResource(PaymentEntitlementGuard.RESOURCE).isEmpty());
        when(payment.getEntitlement(userId)).thenThrow(httpFailure(503));
        for (int i = 0; i < 10; i++) {
            assertFalse(assertUnavailable(guard).getMessage().contains("temporarily blocked"));
        }
        doReturn(entitlement).when(payment).getEntitlement(userId);

        assertSame(entitlement, guard.getEntitlement(userId));

        verify(payment, times(11)).getEntitlement(userId);
    }

    @Test
    void configurationRetainsRulesOwnedByOtherResources() {
        String otherResource = "other-test-resource";
        DegradeRule other = new DegradeRule(otherResource)
                .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
                .setCount(0.5)
                .setMinRequestAmount(5)
                .setStatIntervalMs(10_000)
                .setTimeWindow(10);
        DegradeRuleManager.setRulesForResource(otherResource, Set.of(other));
        try {
            guard();

            assertEquals(Set.of(other), DegradeRuleManager.getRulesOfResource(otherResource));
            DegradeRule rule = DegradeRuleManager.getRulesOfResource(PaymentEntitlementGuard.RESOURCE)
                    .iterator().next();
            assertEquals(0.5, rule.getCount());
            assertEquals(5, rule.getMinRequestAmount());
            assertEquals(10_000, rule.getStatIntervalMs());
            assertEquals(10, rule.getTimeWindow());
        } finally {
            DegradeRuleManager.setRulesForResource(otherResource, Set.of());
        }
    }

    private PaymentEntitlementGuard guard() {
        return new PaymentEntitlementBreakerConfiguration().paymentEntitlementGuard(payment, properties);
    }

    private ApiException assertUnavailable(PaymentEntitlementGuard guard) {
        ApiException failure = assertThrows(ApiException.class, () -> guard.getEntitlement(userId));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        assertEquals("PAYMENT_ENTITLEMENT_UNAVAILABLE", failure.code());
        return failure;
    }

    private FeignException httpFailure(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://payment.stub/internal/payment/entitlement",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                new RequestTemplate());
        return FeignException.errorStatus("AgentPaymentClient#getEntitlement", Response.builder()
                .status(status)
                .reason("stub")
                .request(request)
                .headers(Map.of())
                .build());
    }
}
