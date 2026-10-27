package com.xianda.freshdelivery.delivery.dto;

public record DeliveryConfigItemDto(
        String key,
        String value,
        String valueType,
        String category,
        String displayName,
        String description,
        String minValue,
        String maxValue,
        Boolean editable
) {}
