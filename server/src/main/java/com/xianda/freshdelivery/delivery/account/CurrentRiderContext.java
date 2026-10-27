package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;

public final class CurrentRiderContext {
    private static final ThreadLocal<Long> RIDER_ID = new ThreadLocal<>();

    private CurrentRiderContext() {
    }

    public static Long riderId() {
        Long riderId = RIDER_ID.get();
        if (riderId == null) {
            throw new DeliveryException(DeliveryErrorCode.RIDER_UNAUTHORIZED);
        }
        return riderId;
    }

    public static void setRiderId(Long riderId) {
        RIDER_ID.set(riderId);
    }

    public static void clear() {
        RIDER_ID.remove();
    }
}
