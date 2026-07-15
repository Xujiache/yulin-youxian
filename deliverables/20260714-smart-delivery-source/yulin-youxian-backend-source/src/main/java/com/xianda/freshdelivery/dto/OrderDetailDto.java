package com.xianda.freshdelivery.dto;

import java.util.List;

public record OrderDetailDto(
        Long id,
        String orderNo,
        String status,
        AddressDto address,
        String deliverySlot,
        List<OrderItemDto> items,
        Integer productAmount,
        Integer deliveryFee,
        Integer packageFee,
        Integer payableAmount,
        Integer paidAmount,
        Integer refundedAmount,
        String remark,
        String createdAt,
        String latestRefundStatus,
        String latestRefundReason,
        Long userId,
        List<RefundDto> refunds
) {
}
