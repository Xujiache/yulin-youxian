package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;

public record RoutingTaskRow(
        long taskId,
        Long waveId,
        String status,
        Double lat,
        Double lng,
        String coldChainLevel,
        LocalDateTime windowStartAt,
        LocalDateTime windowEndAt,
        LocalDateTime promisedAt,
        String groupKey,
        String areaLabel,
        String buildingLabel,
        Integer floorNo,
        Integer extraTimeSeconds,
        String extraTimeReason,
        Integer handoffSeconds,
        LocalDateTime pickedReadyAt,
        LocalDateTime arrivedAt,
        LocalDateTime deliveredAt
) {
    public GeoPoint location() {
        return new GeoPoint(lat, lng);
    }

    public boolean finished() {
        return "DELIVERED".equals(status) || "RETURNED".equals(status) || "CANCELLED".equals(status)
                || "CLOSED".equals(status);
    }
}
