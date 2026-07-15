package com.xianda.freshdelivery.dto;

import java.util.List;

public record AdminOrderDto(
        Long id,
        String orderNo,
        String status,
        Integer totalAmount,
        String deliverySlot,
        String summary,
        List<String> images,
        String createdAt,
        String latestRefundStatus,
        String latestRefundReason,
        AddressDto address,
        String deliveryDate,
        String deliveryArea,
        String deliveryBuilding,
        String deliveryGroupKey,
        Integer deliverySequence,
        Integer buildingOrderCount,
        Integer buildingOrderPosition,
        Integer sameAddressOrderCount
) {
}
