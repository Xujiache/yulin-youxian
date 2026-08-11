package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record BroadcastRequest(
        String title,
        String content,
        List<Long> riderIds,
        String priority,
        Boolean needVoice
) {}
