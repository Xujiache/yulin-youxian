package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryWaveStop(
        Long id,
        Long waveId,
        Long taskId,
        Integer seqNo,
        Integer originalSeqNo,
        Double lat,
        Double lng,
        Integer legDistanceMeters,
        Integer legDurationSeconds,
        Integer handoffEstimateSeconds,
        LocalDateTime planArriveAt,
        LocalDateTime planDepartAt,
        LocalDateTime actualArriveAt,
        LocalDateTime actualDepartAt,
        Boolean adjustedByRider,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
