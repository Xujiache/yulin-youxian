package com.xianda.freshdelivery.dto;

public record PaymentConfirmationResult(
        OrderDetailDto order,
        boolean newlyPaid
) {
}
