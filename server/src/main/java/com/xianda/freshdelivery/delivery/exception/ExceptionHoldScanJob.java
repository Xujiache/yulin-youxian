package com.xianda.freshdelivery.delivery.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExceptionHoldScanJob {
    private static final Logger log = LoggerFactory.getLogger(ExceptionHoldScanJob.class);

    private final DeliveryExceptionService deliveryExceptionService;

    public ExceptionHoldScanJob(DeliveryExceptionService deliveryExceptionService) {
        this.deliveryExceptionService = deliveryExceptionService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void scanExpiredHolds() {
        try {
            int released = deliveryExceptionService.releaseExpiredHolds();
            if (released > 0) {
                log.info("异常挂起到期 {} 条，已转入待处理", released);
            }
        } catch (RuntimeException exception) {
            log.warn("异常挂起到期扫描失败：{}", exception.getMessage());
        }
    }
}
