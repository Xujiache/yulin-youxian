package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record RiderMessage(
        Long id,
        Long riderId,
        String messageType,
        String title,
        String content,
        String linkType,
        String linkTarget,
        String priority,
        Boolean needVoice,
        Boolean needAck,
        LocalDateTime ackedAt,
        LocalDateTime readAt,
        String pushStatus,
        String pushError,
        LocalDateTime expireAt,
        LocalDateTime createdAt
) {}
