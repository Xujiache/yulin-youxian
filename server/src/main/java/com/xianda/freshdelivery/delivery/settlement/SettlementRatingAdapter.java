package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.tracking.TrackingRatingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SettlementRatingAdapter implements TrackingRatingPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementRatingAdapter.class);

    private final RiderScoreService riderScoreService;
    private final SettlementTaskQueryDao taskQueryDao;
    private final SettlementRatingDao ratingDao;

    public SettlementRatingAdapter(
            RiderScoreService riderScoreService,
            SettlementTaskQueryDao taskQueryDao,
            SettlementRatingDao ratingDao
    ) {
        this.riderScoreService = riderScoreService;
        this.taskQueryDao = taskQueryDao;
        this.ratingDao = ratingDao;
    }

    @Override
    public void onRating(long taskId, int star, boolean waived) {
        SettlementTaskQueryDao.TaskEarningRow task = taskQueryDao.findEarningRow(taskId).orElse(null);
        if (task == null || !"DELIVERED".equals(task.status())) {
            // WxTrackingService invokes this port immediately after inserting the
            // rating. Compensate that insert because tracking is outside A3's edit
            // boundary, then surface a hard rejection to the API caller.
            ratingDao.deleteByTask(taskId);
            throw new DeliveryException(409, "只有已送达任务可以评价");
        }
        try {
            riderScoreService.onRating(taskId, star, waived);
        } catch (RuntimeException exception) {
            LOGGER.warn("任务 {} 评价服务分记账失败：{}", taskId, exception.getMessage());
        }
    }
}
