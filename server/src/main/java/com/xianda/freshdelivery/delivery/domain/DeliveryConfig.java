package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryConfig(
        String configKey,
        String configValue,
        String valueType,
        String category,
        String displayName,
        String description,
        String minValue,
        String maxValue,
        Boolean editable,
        String updatedBy,
        LocalDateTime updatedAt
) {}
