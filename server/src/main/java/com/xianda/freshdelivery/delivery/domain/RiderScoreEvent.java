package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record RiderScoreEvent(
        Long id,
        Long riderId,
        Long taskId,
        String eventCode,
        Integer scoreDelta,
        Integer scoreAfter,
        String reason,
        Boolean restorable,
        LocalDateTime restoredAt,
        String operatorType,
        String operatorName,
        LocalDateTime createdAt
) {}
