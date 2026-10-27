package com.xianda.freshdelivery.delivery.dispatch;

import java.util.List;

public record DispatchRoundResult(
        int pendingTaskCount,
        int candidateTaskCount,
        int clusterCount,
        int assignedTaskCount,
        int waveCreatedCount,
        int deferredTaskCount,
        int reassignedTaskCount,
        List<String> alerts
) {
    public static final DispatchRoundResult SKIPPED =
            new DispatchRoundResult(0, 0, 0, 0, 0, 0, 0, List.of());

    public DispatchRoundResult {
        alerts = List.copyOf(alerts);
    }
}
