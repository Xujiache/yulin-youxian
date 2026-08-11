package com.xianda.freshdelivery.lottery;

public interface LotteryOrderLifecycle {
    void lockForPaymentShare(long orderId, long userId);

    void onOrderCancelled(long orderId, String reason);

    void onOrderExpired(long orderId);

    void onOrderRestarting(long orderId);

    void onOrderRestartFailed(long orderId);

    void onOrderFulfilled(long orderId);

    void onOrderFullyRefunded(long orderId, boolean orderAlreadyFulfilled);
}
