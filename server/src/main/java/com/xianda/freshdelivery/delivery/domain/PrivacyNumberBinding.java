package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record PrivacyNumberBinding(
        Long id,
        Long taskId,
        Long riderId,
        String provider,
        String subscriptionId,
        String privacyNumber,
        String phoneA,
        String phoneB,
        String status,
        Integer callCount,
        LocalDateTime expireAt,
        LocalDateTime releasedAt,
        LocalDateTime createdAt
) {}
