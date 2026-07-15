package com.xianda.freshdelivery.dto;

import java.util.List;

public record RefundDto(
        Long id,
        Long orderId,
        String refundNo,
        Integer refundAmount,
        String reason,
        String status,
        List<String> evidenceImages,
        Long userId,
        String orderNo,
        String source,
        String createdAt
) {
}
