package com.xianda.freshdelivery.delivery.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopPushService implements PushService {
    public static final String NAME = "NOOP";
    public static final String SKIP_REASON = "未配置推送服务商，已降级为骑手端轮询获取";

    private static final Logger log = LoggerFactory.getLogger(NoopPushService.class);

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public PushResult push(PushRequest request) {
        log.debug("推送已降级：riderId={} type={} title={}", request.riderId(), request.messageType(), request.title());
        return PushResult.skipped(SKIP_REASON);
    }
}
