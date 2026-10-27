package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;

public record RoutingStopRow(
        long waveId,
        long taskId,
        Integer seqNo,
        Integer originalSeqNo,
        Double lat,
        Double lng,
        Integer legDistanceMeters,
        Integer legDurationSeconds,
        Integer handoffEstimateSeconds,
        LocalDateTime planArriveAt,
        LocalDateTime planDepartAt,
        LocalDateTime actualArriveAt,
        LocalDateTime actualDepartAt,
        boolean adjustedByRider
) {
}
