package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;

public record RoutingWaveRow(
        long waveId,
        String waveNo,
        Long riderId,
        String status,
        LocalDateTime assignedAt,
        LocalDateTime startedAt,
        Long routePlanId
) {
}
