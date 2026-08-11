package com.xianda.freshdelivery.delivery.dispatch;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RouteEvaluation(
        int totalDistanceMeters,
        int totalDurationSeconds,
        Map<Long, LocalDateTime> arrivals,
        Long minSlackSeconds,
        List<Long> overtimeTaskIds,
        double maxColdChainRatio,
        List<Long> coldChainRiskTaskIds,
        List<Long> unlocatedTaskIds
) {
    public static final RouteEvaluation EMPTY = new RouteEvaluation(
            0, 0, Map.of(), null, List.of(), 0d, List.of(), List.of());

    public boolean causesOvertime() {
        return !overtimeTaskIds.isEmpty();
    }

    public boolean coldChainAtRisk() {
        return !coldChainRiskTaskIds.isEmpty();
    }
}
