package com.xianda.freshdelivery.lottery;

import java.time.LocalDateTime;

public interface LotteryOrderLifecycle {
    void lockForPaymentShare(long orderId, long userId);

    void onOrderCancelled(long orderId, String reason);

    void onOrderExpired(long orderId);

    void onOrderPaid(long orderId, LocalDateTime paidAt);

    void onOrderRestarting(long orderId);

    void onOrderRestartFailed(long orderId);

    void onOrderFulfilled(long orderId);

    /**
     * @param giftAlreadyDispatched 赠品是否已经随订单发出，已发出的不回补库存。
     */
    void onOrderFullyRefunded(long orderId, boolean giftAlreadyDispatched);
}
