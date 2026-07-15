package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotBlank;

public record PaymentNotifyRequest(
        @NotBlank String orderNo,
        @NotBlank String transactionId,
        @NotBlank String tradeState
) {
}
