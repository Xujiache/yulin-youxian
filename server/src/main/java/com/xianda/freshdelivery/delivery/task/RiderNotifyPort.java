package com.xianda.freshdelivery.delivery.task;

public interface RiderNotifyPort {
    void send(
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
