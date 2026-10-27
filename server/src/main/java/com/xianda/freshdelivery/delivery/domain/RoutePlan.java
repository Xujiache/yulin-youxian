package com.xianda.freshdelivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RoutePlan(
        Long id,
        Long waveId,
        Long riderId,
        Integer planVersion,
        String triggerReason,
        String optimizerName,
        String matrixProvider,
        Integer stopCount,
        Integer totalDistanceMeters,
        Integer totalDurationSeconds,
        BigDecimal objectiveValue,
        Integer solveMillis,
        String sequenceJson,
        String polyline,
        Boolean isActive,
        LocalDateTime createdAt
) {}
