package org.javaup.ai.config;

import java.util.Set;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import org.javaup.ai.client.AgentPaymentClient;
import org.javaup.ai.client.PaymentEntitlementGuard;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentEntitlementBreakerProperties.class)
public class PaymentEntitlementBreakerConfiguration {

    @Bean
    public PaymentEntitlementGuard paymentEntitlementGuard(
            AgentPaymentClient payment,
            PaymentEntitlementBreakerProperties properties) {
        Set<DegradeRule> rules = Set.of();
        if (properties.isEnabled()) {
            DegradeRule rule = new DegradeRule(PaymentEntitlementGuard.RESOURCE)
                    .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
                    .setCount(properties.getExceptionRatio())
                    .setMinRequestAmount(properties.getMinimumRequestAmount())
                    .setStatIntervalMs(properties.getStatIntervalMs())
                    .setTimeWindow(properties.getOpenSeconds());
            if (!DegradeRuleManager.isValidRule(rule)) {
                throw new IllegalArgumentException("Invalid Payment entitlement circuit breaker rule");
            }
            rules = Set.of(rule);
        }
        // Replace only this resource; retain rules registered by other boundaries.
        DegradeRuleManager.setRulesForResource(PaymentEntitlementGuard.RESOURCE, rules);
        return new PaymentEntitlementGuard(payment, properties);
    }
}
