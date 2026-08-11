package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;

public record RouteStop(
        long taskId,
        GeoPoint location,
        ColdChainLevel coldChainLevel,
        LocalDateTime windowStartAt,
        LocalDateTime windowEndAt,
        LocalDateTime dueAt,
        int handoffSeconds,
        String groupKey,
        Integer floorNo
) {
    public static ColdChainLevel parseColdChain(String raw) {
        if (raw == null || raw.isBlank()) {
            return ColdChainLevel.NORMAL;
        }
        try {
            return ColdChainLevel.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ColdChainLevel.NORMAL;
        }
    }

    public String clusterKey() {
        return groupKey == null || groupKey.isBlank() ? "TASK#" + taskId : groupKey.trim();
    }

    public Integer maxExposureSeconds() {
        return coldChainLevel == null ? null : coldChainLevel.maxExposureSeconds();
    }
}
