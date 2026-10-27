package com.xianda.freshdelivery.delivery.task;

public interface TaskSettlementPort {

    void settleTask(long taskId);

    void onTaskDelivered(long taskId, boolean onTime);
}
