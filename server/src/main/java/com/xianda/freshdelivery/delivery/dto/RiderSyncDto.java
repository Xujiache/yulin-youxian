package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record RiderSyncDto(
        String serverTime,
        Boolean hasNewTask,
        Integer pendingAcceptCount,
        Long taskVersion,
        Integer unreadMessageCount,
        List<RiderMessageDto> urgentMessages,
        FatigueDto fatigue,
        SyncConfigDto config
) {
    public record SyncConfigDto(
            Integer reportIntervalSeconds,
            Integer syncIntervalSeconds
    ) {}
}
