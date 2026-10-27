package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.DeliverySwitch;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);

    private final DispatchEngine dispatchEngine;
    private final DispatchSettings settings;
    private final ObjectProvider<DeliveryProperties> deliveryProperties;
    private final DeliverySwitch deliverySwitch;
    private final ReentrantLock lock = new ReentrantLock();

    public DispatchScheduler(DispatchEngine dispatchEngine,
                             DispatchSettings settings,
                             ObjectProvider<DeliveryProperties> deliveryProperties,
                             DeliverySwitch deliverySwitch) {
        this.dispatchEngine = dispatchEngine;
        this.settings = settings;
        this.deliveryProperties = deliveryProperties;
        this.deliverySwitch = deliverySwitch;
    }

    @Scheduled(fixedDelayString = "${delivery.dispatch.loop-interval-ms:5000}", initialDelay = 15000)
    public void tick() {
        // 总闸优先于 dispatch.enabled:后者只停自动派单,前者停整个配送域
        if (!deliverySwitch.enabled() || !loopEnabled() || !settings.autoDispatchAllowed()) {
            return;
        }
        runGuarded(false);
    }

    /**
     * 手动触发(后台「立即调度」)同样受总闸约束,关闸后要给出明确错误而不是静默跳过。
     *
     * 推荐模式下也要拒绝:这个按钮会真的把单派出去,而推荐模式的约定就是「不替店主做主」。
     * 原来它绕过所有开关,点一下就能把店主还没分完的单全派走。
     */
    public DispatchRoundResult runNow() {
        deliverySwitch.ensureEnabled();
        if (!settings.dispatchMode().isAuto()) {
            throw new DeliveryException(
                    DeliveryErrorCode.DISPATCH_ADVISORY_ONLY,
                    "当前是推荐模式，系统不会自动派单。请在调度台选择骑手后发车，或把派单模式改为全自动。"
            );
        }
        return runGuarded(true);
    }

    private DispatchRoundResult runGuarded(boolean manual) {
        if (!lock.tryLock()) {
            if (manual) {
                log.info("上一轮调度尚未结束，手动触发被跳过");
            }
            return DispatchRoundResult.SKIPPED;
        }
        try {
            return dispatchEngine.runOnce();
        } catch (RuntimeException exception) {
            log.error("调度循环执行失败，本轮跳过，待派任务保留在队列：{}", exception.getMessage(), exception);
            return DispatchRoundResult.SKIPPED;
        } finally {
            lock.unlock();
        }
    }

    private boolean loopEnabled() {
        DeliveryProperties properties = deliveryProperties.getIfAvailable();
        return properties == null || properties.dispatch() == null || properties.dispatch().loopEnabled();
    }
}
