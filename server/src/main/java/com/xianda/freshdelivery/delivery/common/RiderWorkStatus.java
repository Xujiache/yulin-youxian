package com.xianda.freshdelivery.delivery.common;

public enum RiderWorkStatus {
    OFF_DUTY("下班"),
    ON_DUTY("上班"),
    RESTING("休息中"),
    BUSY("忙碌中");

    private final String displayName;

    RiderWorkStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
