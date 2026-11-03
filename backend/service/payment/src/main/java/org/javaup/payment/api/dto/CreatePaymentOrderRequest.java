package org.javaup.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePaymentOrderRequest(@NotBlank @Size(max = 40) String planCode) {
}
