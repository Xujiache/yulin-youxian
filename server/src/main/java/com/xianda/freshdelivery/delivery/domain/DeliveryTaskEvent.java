package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryTaskEvent(
        Long id,
        Long taskId,
        String taskNo,
        Long waveId,
        String eventType,
        String fromStatus,
        String toStatus,
        String operatorType,
        Long operatorId,
        String operatorName,
        String reason,
        String detailJson,
        Double lat,
        Double lng,
        LocalDateTime clientEventAt,
        LocalDateTime createdAt
) {}
