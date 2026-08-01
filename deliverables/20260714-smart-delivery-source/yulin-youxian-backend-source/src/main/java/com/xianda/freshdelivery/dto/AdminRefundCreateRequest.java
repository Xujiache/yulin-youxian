package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminRefundCreateRequest(
        @NotNull Long userId,
        @NotNull Long orderId,
        @NotNull @Min(1) Integer refundAmount,
        @NotBlank String reason
) {
}
