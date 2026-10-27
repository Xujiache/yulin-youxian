package com.xianda.freshdelivery.delivery.dispatch;

public record ReassignmentDecision(
        long taskId,
        Long fromRiderId,
        Long toRiderId,
        RiskLevel fromRisk,
        RiskLevel toRisk,
        String reason,
        boolean applied
) {
    public static ReassignmentDecision skipped(long taskId, Long fromRiderId, RiskLevel fromRisk, String reason) {
        return new ReassignmentDecision(taskId, fromRiderId, null, fromRisk, fromRisk, reason, false);
    }
}
