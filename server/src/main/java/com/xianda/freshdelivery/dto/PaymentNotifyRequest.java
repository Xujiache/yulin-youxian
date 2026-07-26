package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotBlank;

public record PaymentNotifyRequest(
        @NotBlank String orderNo,
        @NotBlank String transactionId,
        @NotBlank String tradeState,
        String appId,
        String mchId,
        Integer totalAmount
) {
    public PaymentNotifyRequest(String orderNo, String transactionId, String tradeState) {
        this(orderNo, transactionId, tradeState, "", "", null);
    }
}
