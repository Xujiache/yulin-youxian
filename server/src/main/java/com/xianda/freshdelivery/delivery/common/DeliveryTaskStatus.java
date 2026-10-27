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
            // 异常解除后要能回到进入异常之前的那一步。原来只允许回 DELIVERING，
            // 结果「接单前上报异常 → 调度点继续」会直接把任务推成配送中，
            // 跳过接单和取货两个环节，取货时间、交接耗时这些指标全部为空。
            case EXCEPTION -> target == ACCEPTED || target == PICKED_UP || target == DELIVERING
                    || target == ARRIVED || target == RETURNED || target == CANCELLED;
            case DELIVERED, RETURNED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }

    /**
     * 骑手这边已经没活可干。
     *
     * EXCEPTION 不是终态——调度还要处理那一单——但骑手不能再推进。
     * 若按终态判断能不能回店，上报「顾客联系不上」之后「我已回店」永远出不来，
     * 这一波收不了尾，下一个时段也发不出去。
     */
    public boolean isRiderSettled() {
        return isTerminal() || this == EXCEPTION;
    }

    /** 是否是「骑手还在处理」的中间态。异常解除时用来判断能不能原样恢复。 */
    public boolean isResumable() {
        return this == ACCEPTED || this == PICKED_UP || this == DELIVERING || this == ARRIVED;
    }
}
