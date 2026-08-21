package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotBlank;

public record RefundNotifyRequest(
        @NotBlank String refundNo,
        @NotBlank String refundStatus,
        String paymentOrderNo,
        String transactionId,
        Integer refundAmount,
        Integer totalAmount,
        String wechatRefundId
) {
    public RefundNotifyRequest(String refundNo, String refundStatus) {
        this(refundNo, refundStatus, "", "", null, null, "");
    }
}
