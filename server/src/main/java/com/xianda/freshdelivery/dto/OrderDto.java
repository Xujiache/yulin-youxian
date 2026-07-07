package com.xianda.freshdelivery.dto;

import java.util.List;

public record OrderDto(
        Long id,
        String orderNo,
        String status,
        Integer totalAmount,
        String deliverySlot,
        String summary,
        List<String> images,
        String createdAt,
        String latestRefundStatus,
        String latestRefundReason
) {
}
