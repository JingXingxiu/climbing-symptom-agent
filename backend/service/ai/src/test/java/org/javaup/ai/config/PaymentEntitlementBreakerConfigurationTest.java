package org.javaup.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Set;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.javaup.ai.client.AgentPaymentClient;
import org.javaup.ai.client.PaymentEntitlementGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentEntitlementBreakerConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentEntitlementBreakerConfiguration.class)
            .withBean(AgentPaymentClient.class, () -> mock(AgentPaymentClient.class));

    @AfterEach
    void cleanUp() {
        DegradeRuleManager.setRulesForResource(PaymentEntitlementGuard.RESOURCE, Set.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "exception-ratio=-0.1",
        "exception-ratio=1.1",
        "minimum-request-amount=0",
        "stat-interval-ms=0",
        "open-seconds=0",
        "open-seconds=2147484"
    })
    void invalidConfigurationFailsStartup(String invalidProperty) {
        runner.withPropertyValues("ai.payment-entitlement-breaker." + invalidProperty)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void validConfigurationIsBoundAndInstalledWithoutDashboard() {
        runner.withPropertyValues(
                        "ai.payment-entitlement-breaker.exception-ratio=0.75",
                        "ai.payment-entitlement-breaker.minimum-request-amount=12",
                        "ai.payment-entitlement-breaker.stat-interval-ms=20000",
                        "ai.payment-entitlement-breaker.open-seconds=30")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PaymentEntitlementGuard.class);
                    var rule = DegradeRuleManager.getRulesOfResource(PaymentEntitlementGuard.RESOURCE)
                            .iterator().next();
                    assertThat(rule.getCount()).isEqualTo(0.75);
                    assertThat(rule.getMinRequestAmount()).isEqualTo(12);
                    assertThat(rule.getStatIntervalMs()).isEqualTo(20_000);
                    assertThat(rule.getTimeWindow()).isEqualTo(30);
                });
    }

    @Test
    void configurationCanDisableTheRule() {
        runner.withPropertyValues("ai.payment-entitlement-breaker.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PaymentEntitlementGuard.class);
                    assertThat(DegradeRuleManager.getRulesOfResource(PaymentEntitlementGuard.RESOURCE))
                            .isEmpty();
                });
    }
}
