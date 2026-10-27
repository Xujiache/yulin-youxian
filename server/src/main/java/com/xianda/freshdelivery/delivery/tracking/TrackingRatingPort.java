package com.xianda.freshdelivery.delivery.tracking;

public interface TrackingRatingPort {

    void onRating(long taskId, int star, boolean waived);
}
