package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;

public record RiderCandidateRow(
        long riderId,
        String riderNo,
        String name,
        String accountStatus,
        String workStatus,
        String vehicleType,
        int maxConcurrentTask,
        double capacityWeightKg,
        boolean probation,
        int serviceScore,
        String levelCode,
        Long shiftId,
        LocalDateTime dispatchPausedUntil,
        Double lat,
        Double lng,
        LocalDateTime locatedAt,
        Long currentWaveId
) {
    public GeoPoint location() {
        return lat == null || lng == null ? null : new GeoPoint(lat, lng);
    }

    public boolean onDuty() {
        return "ON_DUTY".equalsIgnoreCase(workStatus);
    }

    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(accountStatus);
    }

    public boolean fatiguePaused(LocalDateTime now) {
        return dispatchPausedUntil != null && dispatchPausedUntil.isAfter(now);
    }

    public boolean locationStale(LocalDateTime now, int livenessTimeoutSeconds) {
        return locatedAt == null || locatedAt.plusSeconds(livenessTimeoutSeconds).isBefore(now);
    }
}
