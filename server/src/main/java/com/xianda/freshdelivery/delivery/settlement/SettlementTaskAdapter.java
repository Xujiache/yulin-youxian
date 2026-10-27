package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.task.TaskSettlementPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SettlementTaskAdapter implements TaskSettlementPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementTaskAdapter.class);

    private final EarningRuleEngine earningRuleEngine;
    private final RiderScoreService riderScoreService;

    public SettlementTaskAdapter(EarningRuleEngine earningRuleEngine, RiderScoreService riderScoreService) {
        this.earningRuleEngine = earningRuleEngine;
        this.riderScoreService = riderScoreService;
    }

    @Override
    public void settleTask(long taskId) {
        try {
            earningRuleEngine.settleTask(taskId);
        } catch (RuntimeException exception) {
            LOGGER.warn("任务 {} 收入结算失败，将由定时扫描兜底：{}", taskId, exception.getMessage());
        }
    }

    @Override
    public void onTaskDelivered(long taskId, boolean onTime) {
        try {
            riderScoreService.onTaskDelivered(taskId, onTime);
        } catch (RuntimeException exception) {
            LOGGER.warn("任务 {} 服务分记账失败：{}", taskId, exception.getMessage());
        }
    }
}
