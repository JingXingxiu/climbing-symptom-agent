package org.javaup.payment.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.javaup.payment.api.dto.PaymentEntitlementResponse;
import org.javaup.payment.service.PaymentService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@AllArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @GetMapping(value = "/internal/payment/entitlement", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaymentEntitlementResponse getEntitlement(@RequestHeader("X-User-Id") @NotNull UUID userId) {
        return paymentService.getEntitlement(userId);
    }
}
