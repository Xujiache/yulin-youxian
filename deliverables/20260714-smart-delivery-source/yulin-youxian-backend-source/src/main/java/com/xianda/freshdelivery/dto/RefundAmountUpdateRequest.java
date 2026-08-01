package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RefundAmountUpdateRequest(
        @NotNull @Min(1) Integer refundAmount
) {
}
