package com.xianda.freshdelivery.delivery.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.DeliverySwitch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 派单模式。
 *
 * 自营单店是商家自己备货、自己分单、按时段发车，系统不该在店主还没决定给谁的时候
 * 就把单派走。默认 ADVISORY 就是这个意思：照常打分推荐，但不落地。
 */
class DispatchModeTests {

    @Test
    void 没配置时默认只推荐不执行() {
        DispatchSettings settings = DispatchTestSupport.settings(
                DispatchTestSupport.defaultConfig().put(DispatchConfigKeys.MODE, null));

        assertEquals(DispatchMode.ADVISORY, settings.dispatchMode());
        assertFalse(settings.autoDispatchAllowed());
    }

    @Test
    void 认不出来的模式按只推荐处理() {
        DispatchSettings settings = DispatchTestSupport.settings(
                DispatchTestSupport.defaultConfig().put(DispatchConfigKeys.MODE, "全自动"));

        assertEquals(DispatchMode.ADVISORY, settings.dispatchMode(),
                "配置写错时宁可不派，也不能替店主做主");
    }

    @Test
    void 推荐模式下调度循环不派单() {
        CountingEngine engine = advisoryEngine();
        newScheduler(engine).tick();

        assertEquals(0, engine.runs.get());
    }

    @Test
    void 推荐模式下立即调度要明确报错而不是静默跳过() {
        CountingEngine engine = advisoryEngine();

        DeliveryException exception = assertThrows(DeliveryException.class, newScheduler(engine)::runNow);

        assertEquals(DeliveryErrorCode.DISPATCH_ADVISORY_ONLY, exception.code());
        assertEquals(0, engine.runs.get(), "这个按钮会真的把单派出去，推荐模式下不能放行");
    }

    @Test
    void 推荐模式下不自动改派() {
        DispatchSettings settings = DispatchTestSupport.settings(
                DispatchTestSupport.defaultConfig()
                        .put(DispatchConfigKeys.MODE, "ADVISORY")
                        .put(DispatchConfigKeys.AUTO_REASSIGN_ENABLED, true));

        assertFalse(settings.autoReassignEnabled(),
                "店主自己挑的骑手，不能因为算出有更优解就换掉");
    }

    @Test
    void 切到全自动后恢复原有行为() {
        DispatchSettings settings = DispatchTestSupport.settings(
                DispatchTestSupport.defaultConfig().put(DispatchConfigKeys.MODE, "auto"));

        assertEquals(DispatchMode.AUTO, settings.dispatchMode());
        assertTrue(settings.autoDispatchAllowed());
        assertTrue(settings.autoReassignEnabled());

        CountingEngine engine = new CountingEngine(settings);
        newScheduler(engine).tick();
        assertEquals(1, engine.runs.get());
    }

    @Test
    void 总开关关掉时全自动也不派() {
        DispatchSettings settings = DispatchTestSupport.settings(
                DispatchTestSupport.defaultConfig()
                        .put(DispatchConfigKeys.MODE, "AUTO")
                        .put(DispatchConfigKeys.ENABLED, false));

        assertFalse(settings.autoDispatchAllowed());
    }

    private static CountingEngine advisoryEngine() {
        return new CountingEngine(DispatchTestSupport.settings(
                DispatchTestSupport.defaultConfig().put(DispatchConfigKeys.MODE, "ADVISORY")));
    }

    private static DispatchScheduler newScheduler(CountingEngine engine) {
        DeliveryProperties properties = new DeliveryProperties(
                true, null, null, null, null, null, null, null);
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
