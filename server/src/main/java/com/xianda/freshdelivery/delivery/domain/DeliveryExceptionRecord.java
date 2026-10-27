package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryExceptionRecord(
        Long id,
        String exceptionNo,
        Long taskId,
        Long waveId,
        Long riderId,
        Long orderId,
        String exceptionType,
        String severity,
        String status,
        String source,
        String description,
        Double lat,
        Double lng,
        LocalDateTime holdUntilAt,
        String resolutionType,
        String resolutionNote,
        Boolean riderExempt,
        String handledBy,
        LocalDateTime handledAt,
        LocalDateTime clientEventAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
