package org.javaup.ai.client;

import java.io.IOException;
import java.util.UUID;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import feign.FeignException;
import org.javaup.ai.api.problem.AIErrorCode;
import org.javaup.ai.api.problem.ApiException;
import org.javaup.ai.client.AgentPaymentClient.EntitlementResponse;
import org.javaup.ai.config.PaymentEntitlementBreakerProperties;
import org.springframework.http.HttpStatus;

/** Protects the shared quota/advance Payment read without fabricating an entitlement. */
public class PaymentEntitlementGuard {

    public static final String RESOURCE = "ai:payment:entitlement";

    private final AgentPaymentClient payment;
    private final boolean enabled;

    public PaymentEntitlementGuard(
            AgentPaymentClient payment,
            PaymentEntitlementBreakerProperties properties) {
        this.payment = payment;
        this.enabled = properties.isEnabled();
    }

    public EntitlementResponse getEntitlement(UUID userId) {
        Entry entry = null;
        try {
            if (enabled) {
                entry = SphU.entry(RESOURCE, EntryType.OUT);
            }
            return payment.getEntitlement(userId);
        } catch (BlockException blocked) {
            throw unavailable("Current AI entitlement lookup is temporarily blocked; retry later");
        } catch (RuntimeException failure) {
            if (entry != null && isDownstreamFailure(failure)) {
                Tracer.traceEntry(failure, entry);
            }
            throw unavailable("Current AI entitlement is unavailable; retry later");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private boolean isDownstreamFailure(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof FeignException remote) {
                return remote.status() < 0 || remote.status() >= 500 && remote.status() <= 599;
            }
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private ApiException unavailable(String detail) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                AIErrorCode.PAYMENT_ENTITLEMENT_UNAVAILABLE,
                detail);
    }
}
