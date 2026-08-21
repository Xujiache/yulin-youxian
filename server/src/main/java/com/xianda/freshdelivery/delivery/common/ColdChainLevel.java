package com.xianda.freshdelivery.delivery.common;

public enum ColdChainLevel {
    NORMAL("常温", null),
    CHILLED("冷藏", 3600),
    FROZEN("冷冻", 2400);

    private final String displayName;
    private final Integer maxExposureSeconds;

    ColdChainLevel(String displayName, Integer maxExposureSeconds) {
        this.displayName = displayName;
        this.maxExposureSeconds = maxExposureSeconds;
    }

    public String displayName() {
        return displayName;
    }

    public Integer maxExposureSeconds() {
        return maxExposureSeconds;
    }
}
