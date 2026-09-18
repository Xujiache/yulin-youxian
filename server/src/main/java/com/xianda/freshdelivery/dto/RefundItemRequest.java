package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RefundItemRequest(
        @NotNull Long orderItemId,
        @NotNull @Min(1) Integer refundAmount
) {}
