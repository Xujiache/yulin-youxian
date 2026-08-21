package com.xianda.freshdelivery.delivery.tracking;

public interface TrackingOrderAccessPort {

    boolean visibleToCurrentUser(long orderId);

    default String currentOpenId() {
        return null;
    }
}
