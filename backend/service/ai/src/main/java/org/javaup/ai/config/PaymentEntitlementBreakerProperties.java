package org.javaup.ai.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("ai.payment-entitlement-breaker")
public class PaymentEntitlementBreakerProperties {

    private boolean enabled = true;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double exceptionRatio = 0.5;

    @Min(1)
    private int minimumRequestAmount = 5;

    @Min(1)
    private int statIntervalMs = 10_000;

    @Min(1)
    @Max(2_147_483)
    private int openSeconds = 10;
}
