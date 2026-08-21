package com.xianda.freshdelivery.delivery.common;

public enum RiderRole {
    RIDER("骑手"),
    RIDER_CAPTAIN("骑手队长");

    private final String displayName;

    RiderRole(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
