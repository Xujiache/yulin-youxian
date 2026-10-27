package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record RiderAppeal(
        Long id,
        String appealNo,
        Long riderId,
        String targetType,
        Long targetId,
        String reason,
        String evidenceIds,
        String status,
        String reviewNote,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
