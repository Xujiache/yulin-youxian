package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record RiderAppChannel(
        String channel,
        Long currentReleaseId,
        int minSupportedVersionCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
