package com.xianda.freshdelivery.delivery.dispatch;

public record ScoreBreakdown(
        double addedDistance,
        double overtimeRisk,
        double loadBalance,
        double coldChain,
        double riderLevel
) {
    public static final ScoreBreakdown ZERO = new ScoreBreakdown(0d, 0d, 0d, 0d, 0d);

    public double sum() {
        return addedDistance + overtimeRisk + loadBalance + coldChain + riderLevel;
    }

    public ScoreBreakdown weightedBy(DispatchSettings.ScoreWeights weights) {
        return new ScoreBreakdown(
                addedDistance * weights.addedDistance(),
                overtimeRisk * weights.overtimeRisk(),
                loadBalance * weights.loadBalance(),
                coldChain * weights.coldChain(),
                riderLevel * weights.riderLevel());
    }
}
