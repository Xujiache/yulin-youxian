package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DistanceMatrixCache(
        Long id,
        String cacheKey,
        String provider,
        String travelMode,
        Double originLat,
        Double originLng,
        Double destLat,
        Double destLng,
        Integer distanceMeters,
        Integer durationSeconds,
        Integer hitCount,
        LocalDateTime expireAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
