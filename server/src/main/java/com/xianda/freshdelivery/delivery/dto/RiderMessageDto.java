package com.xianda.freshdelivery.delivery.dto;

public record RiderMessageDto(
        Long id,
        String messageType,
        String title,
        String content,
        String linkType,
        String linkTarget,
        String priority,
        Boolean needVoice,
        Boolean needAck,
        String ackedAt,
        String readAt,
        String createdAt
) {}
