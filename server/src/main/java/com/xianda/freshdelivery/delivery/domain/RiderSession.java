package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record RiderSession(
        Long id,
        Long riderId,
        String accessToken,
        String refreshToken,
        String deviceId,
        LocalDateTime accessExpireAt,
        LocalDateTime refreshExpireAt,
        LocalDateTime revokedAt,
        String revokeReason,
        LocalDateTime lastActiveAt,
        String clientIp,
        LocalDateTime createdAt
) {}
