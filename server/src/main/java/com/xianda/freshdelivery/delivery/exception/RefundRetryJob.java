package com.xianda.freshdelivery.delivery.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RefundRetryJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(RefundRetryJob.class);

    private final DeliveryExceptionService exceptionService;

    public RefundRetryJob(DeliveryExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 120_000L)
    public void retry() {
        try {
            int completed = exceptionService.retryPendingRefunds();
            if (completed > 0) {
                LOGGER.info("配送异常退款补偿完成 {} 条", completed);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("配送异常退款补偿扫描失败：{}", exception.getMessage());
        }
    }
}
