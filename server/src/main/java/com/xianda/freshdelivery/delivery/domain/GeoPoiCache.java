package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record GeoPoiCache(
        Long id,
        String addressHash,
        String rawAddress,
        String formattedAddress,
        Double lat,
        Double lng,
        Integer confidence,
        String provider,
        Integer hitCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
