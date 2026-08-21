package com.xianda.freshdelivery.delivery.common;

public enum RiderAccountStatus {
    ACTIVE("在职"),
    SUSPENDED("已停用"),
    RESIGNED("已离职");

    private final String displayName;

    RiderAccountStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
