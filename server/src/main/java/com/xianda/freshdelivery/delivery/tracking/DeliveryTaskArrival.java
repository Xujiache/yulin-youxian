package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.task.DeliveryTaskService;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskArrival implements TrackingArrivalPort {
    private final DeliveryTaskService deliveryTaskService;

    public DeliveryTaskArrival(DeliveryTaskService deliveryTaskService) {
        this.deliveryTaskService = deliveryTaskService;
    }

    @Override
    public void autoMarkArrived(long taskId, Double lat, Double lng) {
        deliveryTaskService.autoMarkArrived(taskId, lat, lng);
    }
}
