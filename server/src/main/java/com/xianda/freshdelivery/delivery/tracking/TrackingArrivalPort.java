package com.xianda.freshdelivery.delivery.tracking;

public interface TrackingArrivalPort {

    void autoMarkArrived(long taskId, Double lat, Double lng);
}
