package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryZone(
        Long id,
        String zoneName,
        String zoneType,
        String polygonJson,
        Double centerLat,
        Double centerLng,
        Integer radiusMeters,
        Integer extraTimeSeconds,
        Integer extraFeeAmount,
        Boolean enabled,
        Integer sortOrder,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
