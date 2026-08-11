package com.xianda.freshdelivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record DeliveryWave(
        Long id,
        String waveNo,
        Long riderId,
        String status,
        LocalDate deliveryDate,
        Integer taskCount,
        Integer completedCount,
        BigDecimal totalWeightKg,
        Integer totalItemCount,
        String maxColdChainLevel,
        Integer planDistanceMeters,
        Integer planDurationSeconds,
        Integer actualDistanceMeters,
        LocalDateTime planReturnAt,
        Long routePlanId,
        String optimizerName,
        String matrixProvider,
        LocalDateTime assignedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
