package com.xianda.freshdelivery.dto;

public record PaymentConfirmationResult(
        OrderDetailDto order,
        boolean newlyPaid,
        Long refundId
) {
    public PaymentConfirmationResult(OrderDetailDto order, boolean newlyPaid) {
        this(order, newlyPaid, null);
    }

    public boolean requiresRefundSubmission() {
        return refundId != null;
    }
}
