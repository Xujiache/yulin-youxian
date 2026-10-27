package com.xianda.freshdelivery.delivery.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.DeliverySwitch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * delivery.enabled 总闸对调度循环的作用。与 dispatch.enabled 的区别在这里体现:
 * 后者停自动派单,前者把整个配送域停掉,连手动触发都不许。
 */
class DispatchSchedulerSwitchTests {

    @Test
    void masterSwitchOffKeepsScheduledLoopFromRunning() {
        CountingEngine engine = newEngine();
        DispatchScheduler scheduler = newScheduler(engine, false);

        scheduler.tick();

        assertEquals(0, engine.runs.get());
    }

    @Test
    void masterSwitchOffRejectsManualRunWithClearError() {
        CountingEngine engine = newEngine();
        DispatchScheduler scheduler = newScheduler(engine, false);

        DeliveryException exception = assertThrows(DeliveryException.class, scheduler::runNow);

        assertEquals(DeliveryErrorCode.DELIVERY_DISABLED, exception.code());
        assertEquals(DeliverySwitch.DISABLED_MESSAGE, exception.getMessage());
        assertEquals(0, engine.runs.get());
    }

    @Test
    void masterSwitchOnStillRunsTheLoop() {
        CountingEngine engine = newEngine();
        DispatchScheduler scheduler = newScheduler(engine, true);

        scheduler.tick();

        assertEquals(1, engine.runs.get());
    }

    /** 拿不到 delivery.* 配置时必须按「开」处理,否则配置读失败会静默停掉配送。 */
    @Test
    void missingDeliveryPropertiesKeepTheLoopRunning() {
        CountingEngine engine = newEngine();
        DispatchScheduler scheduler = new DispatchScheduler(
                engine,
                engine.settings,
                new DispatchFakes.SingletonObjectProvider<DeliveryProperties>(null),
                DeliverySwitch.alwaysEnabled());

        scheduler.tick();

        assertEquals(1, engine.runs.get());
    }

    private static CountingEngine newEngine() {
        return new CountingEngine(DispatchTestSupport.settings(DispatchTestSupport.defaultConfig()));
    }

    private static DispatchScheduler newScheduler(CountingEngine engine, boolean deliveryEnabled) {
        DeliveryProperties properties = new DeliveryProperties(
                deliveryEnabled, null, null, null, null, null, null, null);
        return new DispatchScheduler(
                engine,
                engine.settings,
                new DispatchFakes.SingletonObjectProvider<>(properties),
                new DeliverySwitch(new DispatchFakes.SingletonObjectProvider<>(properties)));
    }

    private static final class CountingEngine extends DispatchEngine {
        private final AtomicInteger runs = new AtomicInteger();
        private final DispatchSettings settings;

        private CountingEngine(DispatchSettings settings) {
            super(null, null, null, null, null, null, null, null, null, settings,
                    DispatchTestSupport.fixedClock());
            this.settings = settings;
        }

        @Override
        public DispatchRoundResult runOnce() {
            runs.incrementAndGet();
            return DispatchRoundResult.SKIPPED;
        }
    }
}
