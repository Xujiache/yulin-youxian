package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.routing.EtaEngine;
import com.xianda.freshdelivery.delivery.routing.ReplanTrigger;
import com.xianda.freshdelivery.delivery.routing.RoutePlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RoutingWaveAdapter implements WaveRoutingPort {
    private static final Logger log = LoggerFactory.getLogger(RoutingWaveAdapter.class);

    private final ObjectProvider<RoutePlanService> routePlanServiceProvider;
    private final ObjectProvider<EtaEngine> etaEngineProvider;

    public RoutingWaveAdapter(ObjectProvider<RoutePlanService> routePlanServiceProvider,
                              ObjectProvider<EtaEngine> etaEngineProvider) {
        this.routePlanServiceProvider = routePlanServiceProvider;
        this.etaEngineProvider = etaEngineProvider;
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

    private void plan(long waveId, ReplanTrigger trigger) {
        RoutePlanService service = routePlanServiceProvider.getIfAvailable();
        if (service == null) {
            recomputeWaveEta(waveId);
            return;
        }
        try {
            service.plan(waveId, trigger);
        } catch (RuntimeException exception) {
            log.warn("波次 {} 路径规划({})失败，派单结果保留，仅重算 ETA：{}", waveId, trigger, exception.getMessage());
            recomputeWaveEta(waveId);
        }
    }
}
