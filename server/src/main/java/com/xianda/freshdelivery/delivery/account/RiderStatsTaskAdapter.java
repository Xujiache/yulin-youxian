package com.xianda.freshdelivery.delivery.account;

import com.xianda.freshdelivery.delivery.task.RiderStatsPort;
import org.springframework.stereotype.Component;

@Component
public class RiderStatsTaskAdapter implements RiderStatsPort {
    private final RiderStatsService riderStatsService;

    public RiderStatsTaskAdapter(RiderStatsService riderStatsService) {
        this.riderStatsService = riderStatsService;
    }

    @Override
    public void refreshAfterDelivery(long riderId) {
        riderStatsService.refreshAfterDelivery(riderId);
    }
}
