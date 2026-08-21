package com.xianda.freshdelivery.delivery.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PrivacyNumberReleaseJob {
    private static final Logger log = LoggerFactory.getLogger(PrivacyNumberReleaseJob.class);

    private final PrivacyCallService privacyCallService;

    public PrivacyNumberReleaseJob(PrivacyCallService privacyCallService) {
        this.privacyCallService = privacyCallService;
    }

    @Scheduled(fixedDelay = 600_000L, initialDelay = 120_000L)
    public void releaseExpired() {
        try {
            int released = privacyCallService.releaseExpiredBindings();
            if (released > 0) {
                log.info("隐私号过期绑定释放 {} 条", released);
            }
        } catch (RuntimeException exception) {
            log.warn("隐私号过期绑定释放任务失败：{}", exception.getMessage());
        }
    }
}
