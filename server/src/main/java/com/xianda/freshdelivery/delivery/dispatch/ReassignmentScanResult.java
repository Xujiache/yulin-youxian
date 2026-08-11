package com.xianda.freshdelivery.delivery.dispatch;

import java.util.List;

public record ReassignmentScanResult(
        List<ReassignmentDecision> decisions,
        int overtimeRiskCount
) {
    public static final ReassignmentScanResult EMPTY = new ReassignmentScanResult(List.of(), 0);

    public ReassignmentScanResult {
        decisions = List.copyOf(decisions);
    }

    public int appliedCount() {
        return (int) decisions.stream().filter(ReassignmentDecision::applied).count();
    }
}
