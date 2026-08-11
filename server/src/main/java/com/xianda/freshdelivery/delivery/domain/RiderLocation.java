package com.xianda.freshdelivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RiderLocation(
        Long id,
        Long riderId,
        Long shiftId,
        Long waveId,
        Double lat,
        Double lng,
        Integer accuracyMeters,
        BigDecimal speedMps,
        BigDecimal bearing,
        BigDecimal altitude,
        String provider,
        Integer batteryLevel,
        String networkType,
        String motionState,
        Boolean isCleaned,
        LocalDateTime locatedAt,
        LocalDateTime reportedAt,
        String batchKey
) {}
