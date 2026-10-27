package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryEvidence(
        Long id,
        Long taskId,
        Long exceptionId,
        Long orderId,
        Long riderId,
        String evidenceType,
        String fileUrl,
        Integer fileSize,
        Integer width,
        Integer height,
        Double lat,
        Double lng,
        String watermarkText,
        LocalDateTime capturedAt,
        LocalDateTime uploadedAt
) {}
