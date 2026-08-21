package com.xianda.freshdelivery.delivery.dispatch;

public enum RiskLevel {
    LOW("充裕"),
    MEDIUM("偏紧"),
    HIGH("紧张"),
    OVERTIME("已超时");

    public static final long HIGH_THRESHOLD_SECONDS = 300L;
    public static final long MEDIUM_THRESHOLD_SECONDS = 900L;

    private final String displayName;

    RiskLevel(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean needsIntervention() {
        return this == HIGH || this == OVERTIME;
    }

    public boolean betterThan(RiskLevel other) {
        return other != null && ordinal() < other.ordinal();
    }

    public static RiskLevel of(long slackSeconds) {
        if (slackSeconds <= 0L) {
            return OVERTIME;
        }
        if (slackSeconds <= HIGH_THRESHOLD_SECONDS) {
            return HIGH;
        }
        if (slackSeconds <= MEDIUM_THRESHOLD_SECONDS) {
            return MEDIUM;
        }
        return LOW;
    }
}
