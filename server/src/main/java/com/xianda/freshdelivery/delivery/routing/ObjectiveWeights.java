package com.xianda.freshdelivery.delivery.routing;

public record ObjectiveWeights(double late, double cold, double early) {
    public static final double DEFAULT_LATE = 5.0d;
    public static final double DEFAULT_COLD = 8.0d;
    public static final double DEFAULT_EARLY = 0.5d;

    public ObjectiveWeights {
        if (cold < late) {
            throw new IllegalArgumentException(
                    "冷链惩罚系数必须不小于超时惩罚系数：lambda_cold=" + cold + ", lambda_late=" + late);
        }
    }

    public static ObjectiveWeights defaults() {
        return new ObjectiveWeights(DEFAULT_LATE, DEFAULT_COLD, DEFAULT_EARLY);
    }
}
