package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EarningScanJob {
    private static final Logger log = LoggerFactory.getLogger(EarningScanJob.class);
    private static final int BATCH_SIZE = 300;
    private static final int LOOKBACK_HOURS = 48;

    private final SettlementTaskQueryDao taskQueryDao;
    private final EarningRuleEngine earningRuleEngine;
    private final Clock clock;

    @Autowired
    public EarningScanJob(SettlementTaskQueryDao taskQueryDao, EarningRuleEngine earningRuleEngine) {
        this(taskQueryDao, earningRuleEngine, Clock.system(DeliveryTimes.STORE_ZONE));
    }

    public EarningScanJob(SettlementTaskQueryDao taskQueryDao, EarningRuleEngine earningRuleEngine, Clock clock) {
        this.taskQueryDao = taskQueryDao;
        this.earningRuleEngine = earningRuleEngine;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 300_000L, initialDelay = 180_000L)
    public void settleDeliveredTasks() {
        try {
            log.debug("收入兜底扫描补算 {} 单", scanOnce());
        } catch (RuntimeException exception) {
            log.warn("收入兜底扫描失败：{}", exception.getMessage());
        }
    }

    public int scanOnce() {
        if (earningRuleEngine.familyMode()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> taskIds = taskQueryDao.findDeliveredWithoutEarning(
                now.minusHours(LOOKBACK_HOURS), now.plusMinutes(1), BATCH_SIZE);
        int settled = 0;
        for (Long taskId : taskIds) {
            try {
                earningRuleEngine.settleTask(taskId);
                settled++;
            } catch (RuntimeException exception) {
                log.warn("任务 {} 收入补算失败：{}", taskId, exception.getMessage());
            }
        }
        return settled;
    }
}
