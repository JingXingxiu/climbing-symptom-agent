package org.javaup.payment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.javaup.payment.api.dto.CreatePaymentOrderRequest;
import org.javaup.payment.api.dto.PaymentEntitlementResponse;
import org.javaup.payment.api.dto.PaymentOrderResponse;
import org.javaup.payment.api.dto.PaymentPlanResponse;
import org.javaup.payment.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@AllArgsConstructor
@RequestMapping(value = "/api/payment/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/plans")
    public List<PaymentPlanResponse> listPlans() {
        return paymentService.listPlans();
    }

    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentOrderResponse> createOrder(
            @RequestHeader("X-User-Id") @NotNull UUID userId,
            @RequestHeader("X-Idempotency-Key") @NotNull UUID idempotencyKey,
            @Valid @RequestBody CreatePaymentOrderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createOrder(userId, idempotencyKey, request));
    }

    @GetMapping("/orders")
    public List<PaymentOrderResponse> listOrders(
            @RequestHeader("X-User-Id") @NotNull UUID userId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return paymentService.listOrders(userId, limit);
    }

    @GetMapping("/orders/{orderId}")
    public PaymentOrderResponse getOrder(
            @RequestHeader("X-User-Id") @NotNull UUID userId,
            @PathVariable UUID orderId
    ) {
        return paymentService.getOrder(userId, orderId);
    }

    @PostMapping("/orders/{orderId}/mock-pay")
    public PaymentOrderResponse mockPay(
            @RequestHeader("X-User-Id") @NotNull UUID userId,
            @PathVariable UUID orderId
    ) {
        return paymentService.mockPay(userId, orderId);
    }

    @GetMapping("/entitlement")
    public PaymentEntitlementResponse getEntitlement(@RequestHeader("X-User-Id") @NotNull UUID userId) {
        return paymentService.getEntitlement(userId);
    }
}
