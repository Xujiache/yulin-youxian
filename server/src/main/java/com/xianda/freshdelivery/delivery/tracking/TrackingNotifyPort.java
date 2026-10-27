package com.xianda.freshdelivery.delivery.tracking;

public interface TrackingNotifyPort {
    void notifyRider(
            Long riderId,
            String messageType,
            String title,
            String content,
            String priority,
            boolean needVoice,
            String linkType,
            String linkTarget
    );
}
