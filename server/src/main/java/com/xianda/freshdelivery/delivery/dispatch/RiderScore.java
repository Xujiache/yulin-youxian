package com.xianda.freshdelivery.delivery.dispatch;

import java.time.LocalDateTime;
import java.util.List;

public record RiderScore(
        long riderId,
        String riderName,
        double score,
        ScoreBreakdown factors,
        ScoreBreakdown contributions,
        int addedDistanceMeters,
        int addedDurationSeconds,
        LocalDateTime estimatedArriveAt,
        RiskLevel overtimeRiskAfter,
        double loadRatio,
        List<String> blockers,
        List<String> warnings,
        boolean eligible
) {
    public RiderScore {
        blockers = List.copyOf(blockers);
        warnings = List.copyOf(warnings);
    }

    public boolean fatigueBlocked() {
        return blockers.contains(DispatchCodes.BLOCKER_FATIGUE_PAUSED);
    }
}
