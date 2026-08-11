package com.xianda.freshdelivery.delivery.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderBridgeRetryJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderBridgeRetryJob.class);
    private static final int BATCH_SIZE = 200;

    private final OrderStatusBridge bridge;

    public OrderBridgeRetryJob(OrderStatusBridge bridge) {
        this.bridge = bridge;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void retry() {
        try {
            int repaired = bridge.retryPending(BATCH_SIZE);
            if (repaired > 0) {
                LOGGER.info("订单状态桥补偿完成 {} 条", repaired);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("订单状态桥补偿扫描失败：{}", exception.getMessage());
        }
    }
}
