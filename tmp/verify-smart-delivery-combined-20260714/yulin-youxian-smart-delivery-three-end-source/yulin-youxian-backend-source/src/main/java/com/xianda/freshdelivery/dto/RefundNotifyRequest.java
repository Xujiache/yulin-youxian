package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotBlank;

public record RefundNotifyRequest(
        @NotBlank String refundNo,
        @NotBlank String refundStatus
) {
}
