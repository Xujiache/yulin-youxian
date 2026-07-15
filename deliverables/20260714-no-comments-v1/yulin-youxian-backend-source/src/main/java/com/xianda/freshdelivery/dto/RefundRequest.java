package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record RefundRequest(
        @NotNull Long orderId,
        List<Long> orderItemIds,
        @Min(1) int refundAmount,
        @NotBlank String reason,
        List<String> evidenceImages
) {
}
