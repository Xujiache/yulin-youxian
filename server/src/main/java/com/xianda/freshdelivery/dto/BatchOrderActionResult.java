package com.xianda.freshdelivery.dto;

import java.util.List;

public record BatchOrderActionResult(
        int requested,
        int success,
        int skipped,
        List<Long> processedOrderIds,
        List<BatchOrderActionError> errors
) {
    public record BatchOrderActionError(
            Long orderId,
            String orderNo,
            String reason
    ) {
    }
}
