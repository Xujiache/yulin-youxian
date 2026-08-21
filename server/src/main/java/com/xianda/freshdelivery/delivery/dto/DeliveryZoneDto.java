package com.xianda.freshdelivery.delivery.dto;

public record DeliveryZoneDto(
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
        String createdAt,
        String updatedAt
) {}
