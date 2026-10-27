package com.xianda.freshdelivery.delivery.dto;

public record ScoreEventDto(
        Long id,
        Long taskId,
        String eventCode,
        Integer scoreDelta,
        Integer scoreAfter,
        String reason,
        Boolean restorable,
        String restoredAt,
        String createdAt
) {}
