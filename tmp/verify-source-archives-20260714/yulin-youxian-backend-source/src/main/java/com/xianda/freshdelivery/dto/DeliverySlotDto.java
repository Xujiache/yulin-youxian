package com.xianda.freshdelivery.dto;

public record DeliverySlotDto(
        Long id,
        String label,
        Integer maxOrders,
        boolean available
) {
}
