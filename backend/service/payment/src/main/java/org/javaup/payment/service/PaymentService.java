package org.javaup.payment.service;

import java.util.List;
import java.util.UUID;
import org.javaup.payment.api.dto.CreatePaymentOrderRequest;
import org.javaup.payment.api.dto.PaymentEntitlementResponse;
import org.javaup.payment.api.dto.PaymentOrderResponse;
import org.javaup.payment.api.dto.PaymentPlanResponse;

public interface PaymentService {

    List<PaymentPlanResponse> listPlans();

    PaymentOrderResponse createOrder(UUID userId, UUID idempotencyKey, CreatePaymentOrderRequest request);

    List<PaymentOrderResponse> listOrders(UUID userId, int limit);

    PaymentOrderResponse getOrder(UUID userId, UUID orderId);

    PaymentOrderResponse mockPay(UUID userId, UUID orderId);

    PaymentEntitlementResponse getEntitlement(UUID userId);
}
