package com.xianda.freshdelivery.delivery.common;

public enum DeliveryTaskStatus {
    PENDING("待分配"),
    ASSIGNED("已派单"),
    ACCEPTED("已接单"),
    PICKED_UP("已取货"),
    DELIVERING("配送中"),
    ARRIVED("已到达"),
    DELIVERED("已送达"),
    EXCEPTION("异常中"),
    RETURNED("已退回"),
    CANCELLED("已取消");

    private final String displayName;

    DeliveryTaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean canTransitTo(DeliveryTaskStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == ASSIGNED || target == CANCELLED;
            case ASSIGNED -> target == ACCEPTED || target == PENDING || target == EXCEPTION || target == CANCELLED;
            case ACCEPTED -> target == PICKED_UP || target == PENDING || target == EXCEPTION || target == CANCELLED;
            case PICKED_UP -> target == DELIVERING || target == EXCEPTION;
            case DELIVERING -> target == ARRIVED || target == DELIVERED || target == EXCEPTION;
            case ARRIVED -> target == DELIVERED || target == EXCEPTION;
            case EXCEPTION -> target == DELIVERING || target == RETURNED || target == CANCELLED;
            case DELIVERED, RETURNED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }
}
