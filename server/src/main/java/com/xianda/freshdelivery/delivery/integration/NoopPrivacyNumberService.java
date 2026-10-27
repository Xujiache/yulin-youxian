package com.xianda.freshdelivery.delivery.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopPrivacyNumberService implements PrivacyNumberService {
    public static final String NAME = "NOOP";
    public static final String DEGRADED_NOTICE = "隐私号服务暂不可用，请勿保存顾客号码";

    private static final Logger log = LoggerFactory.getLogger(NoopPrivacyNumberService.class);

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public PrivacyBinding bind(BindRequest request) {
        log.info("隐私号未启用，任务 {} 降级为明文号码并登记 DEGRADED 供合规审计", request.taskId());
        return new PrivacyBinding(NAME, null, request.customerPhone(), true, STATUS_DEGRADED, DEGRADED_NOTICE);
    }

    @Override
    public void release(long bindingId, String subscriptionId) {
        log.debug("隐私号未启用，绑定 {} 无需释放", bindingId);
    }
}
