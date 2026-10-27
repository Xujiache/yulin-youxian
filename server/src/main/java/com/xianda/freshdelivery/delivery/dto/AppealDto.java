package com.xianda.freshdelivery.delivery.dto;

public record AppealDto(
        Long id,
        String appealNo,
        String targetType,
        Long targetId,
        String reason,
        String status,
        String reviewNote,
        String reviewedBy,
        String reviewedAt,
        String createdAt
) {}
