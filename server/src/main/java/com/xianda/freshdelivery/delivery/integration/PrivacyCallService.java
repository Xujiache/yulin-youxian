package com.xianda.freshdelivery.delivery.integration;

import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.PrivacyNumberBinding;
import com.xianda.freshdelivery.delivery.dto.CallNumberDto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PrivacyCallService {
    public static final int BINDING_VALID_HOURS = 4;

    private static final Logger log = LoggerFactory.getLogger(PrivacyCallService.class);
    private static final int RELEASE_BATCH_SIZE = 200;

    private final PrivacyNumberService privacyNumberService;
    private final PrivacyBindingDao privacyBindingDao;
    private final IntegrationTaskContactDao taskContactDao;
    private final Clock clock;

    @Autowired
    public PrivacyCallService(PrivacyNumberService privacyNumberService,
                              PrivacyBindingDao privacyBindingDao,
                              IntegrationTaskContactDao taskContactDao) {
        this(privacyNumberService, privacyBindingDao, taskContactDao, Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public PrivacyCallService(PrivacyNumberService privacyNumberService,
                              PrivacyBindingDao privacyBindingDao,
                              IntegrationTaskContactDao taskContactDao,
                              Clock clock) {
        this.privacyNumberService = privacyNumberService;
        this.privacyBindingDao = privacyBindingDao;
        this.taskContactDao = taskContactDao;
        this.clock = clock;
    }

    public CallNumberDto requestCall(long riderId, long taskId) {
        IntegrationTaskContactDao.TaskContact contact = taskContactDao.findByTaskId(taskId)
                .orElseThrow(() -> new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND));
        if (contact.riderId() == null || contact.riderId() != riderId) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER);
        }
        if (contact.receiverPhone() == null || contact.receiverPhone().isBlank()) {
            throw new DeliveryException(DeliveryErrorCode.PRIVACY_NUMBER_UNAVAILABLE, "订单缺少顾客联系方式");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        PrivacyNumberBinding existing = privacyBindingDao.findLatestUsable(taskId, now).orElse(null);
        if (existing != null) {
            privacyBindingDao.increaseCallCount(existing.id());
            boolean degraded = PrivacyNumberService.STATUS_DEGRADED.equals(existing.status());
            return new CallNumberDto(
                    existing.privacyNumber(),
                    degraded,
                    DeliveryTimes.format(existing.expireAt()),
                    degraded ? NoopPrivacyNumberService.DEGRADED_NOTICE : null);
        }
        LocalDateTime expireAt = now.plusHours(BINDING_VALID_HOURS);
        PrivacyNumberService.PrivacyBinding result = privacyNumberService.bind(
                new PrivacyNumberService.BindRequest(taskId, riderId, contact.riderPhone(),
                        contact.receiverPhone(), expireAt));
        String callNumber = result.callNumber() == null || result.callNumber().isBlank()
                ? contact.receiverPhone()
                : result.callNumber();
        PrivacyNumberBinding binding = new PrivacyNumberBinding(
                null, taskId, riderId, result.provider(), result.subscriptionId(), callNumber,
                contact.riderPhone() == null ? "" : contact.riderPhone(), contact.receiverPhone(),
                result.status(), 1, expireAt, null, now);
        try {
            privacyBindingDao.insert(binding, now);
        } catch (RuntimeException exception) {
            log.warn("隐私号绑定登记失败，任务 {}：{}", taskId, exception.getMessage());
        }
        return new CallNumberDto(
                callNumber,
                result.degraded(),
                DeliveryTimes.format(expireAt),
                result.degraded()
                        ? (result.notice() == null ? NoopPrivacyNumberService.DEGRADED_NOTICE : result.notice())
                        : null);
    }

    public int releaseExpiredBindings() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PrivacyNumberBinding> expired = privacyBindingDao.findExpired(now, RELEASE_BATCH_SIZE);
        int released = 0;
        for (PrivacyNumberBinding binding : expired) {
            try {
                privacyNumberService.release(binding.id(), binding.subscriptionId());
            } catch (RuntimeException exception) {
                log.warn("隐私号解绑失败，绑定 {}：{}", binding.id(), exception.getMessage());
            }
            released += privacyBindingDao.markReleased(binding.id(), now);
        }
        return released;
    }
}
