package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record BroadcastRequest(
        String title,
        String content,
        List<Long> riderIds,
        String priority,
        Boolean needVoice,
        String messageType,
        String linkType,
        String linkTarget
) {
    public BroadcastRequest(String title, String content, List<Long> riderIds, String priority, Boolean needVoice) {
        this(title, content, riderIds, priority, needVoice, null, null, null);
    }
}
