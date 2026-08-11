package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryGeofenceEvent(
        Long id,
        Long riderId,
        Long taskId,
        String zoneType,
        String eventType,
        Double lat,
        Double lng,
        Integer distanceMeters,
        Integer dwellSeconds,
        String autoAction,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {}
