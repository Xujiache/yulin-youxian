package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.routing.EtaEngine;
import com.xianda.freshdelivery.delivery.routing.ReplanTrigger;
import com.xianda.freshdelivery.delivery.routing.RoutePlanFailureDao;
import com.xianda.freshdelivery.delivery.routing.RoutePlanService;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RoutingWaveAdapter implements WaveRoutingPort {
    private static final Logger log = LoggerFactory.getLogger(RoutingWaveAdapter.class);

    private final ObjectProvider<RoutePlanService> routePlanServiceProvider;
    private final ObjectProvider<EtaEngine> etaEngineProvider;
    private final ObjectProvider<RoutePlanFailureDao> failureDaoProvider;

    public RoutingWaveAdapter(ObjectProvider<RoutePlanService> routePlanServiceProvider,
                              ObjectProvider<EtaEngine> etaEngineProvider,
                              ObjectProvider<RoutePlanFailureDao> failureDaoProvider) {
        this.routePlanServiceProvider = routePlanServiceProvider;
        this.etaEngineProvider = etaEngineProvider;
        this.failureDaoProvider = failureDaoProvider;
    }

    @Override
    public void planWave(long waveId, boolean newTaskJoinedExistingWave) {
        plan(waveId, newTaskJoinedExistingWave ? ReplanTrigger.NEW_TASK : ReplanTrigger.INITIAL);
    }

    @Override
    public void replanAfterReassign(long waveId) {
        plan(waveId, ReplanTrigger.REASSIGN);
    }

    @Override
    public void recomputeWaveEta(long waveId) {
        EtaEngine engine = etaEngineProvider.getIfAvailable();
        if (engine == null) {
            return;
        }
        try {
            engine.recomputeWaveEta(waveId);
        } catch (RuntimeException exception) {
            log.warn("波次 {} ETA 重算失败，派单结果保留：{}", waveId, exception.getMessage());
        }
    }

    /**
     * 规划失败不影响派单，但骑手会拿到一个没有路线的波次 —— 地图上只有孤零零几个点。
     * 所以失败必须留痕，否则调度台永远不知道发生过什么。
     */
    private void plan(long waveId, ReplanTrigger trigger) {
        RoutePlanService service = routePlanServiceProvider.getIfAvailable();
        if (service == null) {
            recomputeWaveEta(waveId);
            return;
        }
        try {
            service.plan(waveId, trigger);
            withFailureDao(dao -> dao.clear(waveId));
        } catch (RuntimeException exception) {
            log.warn("波次 {} 路径规划({})失败，派单结果保留，仅重算 ETA：{}", waveId, trigger, exception.getMessage());
            withFailureDao(dao ->
                    dao.record(waveId, trigger.name(), exception.getMessage(), LocalDateTime.now()));
            recomputeWaveEta(waveId);
        }
    }

    /** 留痕本身失败不能反过来打断派单，这里只吞掉并记日志。 */
    private void withFailureDao(Consumer<RoutePlanFailureDao> action) {
        RoutePlanFailureDao dao = failureDaoProvider.getIfAvailable();
        if (dao == null) {
            return;
        }
        try {
            action.accept(dao);
        } catch (RuntimeException exception) {
            log.warn("路径规划失败留痕写入失败：{}", exception.getMessage());
        }
    }
}
