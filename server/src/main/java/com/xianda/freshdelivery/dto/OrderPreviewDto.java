package com.xianda.freshdelivery.dto;

import java.util.List;

public record OrderPreviewDto(
        AddressDto address,
        DeliverySlotDto deliverySlot,
        List<OrderItemDto> items,
        Integer productAmount,
        Integer deliveryFee,
        Integer packageFee,
        Integer payableAmount,
        Boolean deliveryFeeWaived,
        String deliveryFeeNotice
) {
}
