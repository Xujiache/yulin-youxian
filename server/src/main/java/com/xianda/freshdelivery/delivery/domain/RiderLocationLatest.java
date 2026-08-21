package com.xianda.freshdelivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RiderLocationLatest(
        Long riderId,
        Double lat,
        Double lng,
        Integer accuracyMeters,
        BigDecimal speedMps,
        BigDecimal bearing,
        Integer batteryLevel,
        String motionState,
        Long waveId,
        Long currentTaskId,
        LocalDateTime locatedAt,
        LocalDateTime updatedAt
) {}
