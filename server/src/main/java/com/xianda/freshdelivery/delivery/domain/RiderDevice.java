package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record RiderDevice(
        Long id,
        Long riderId,
        String deviceId,
        String manufacturer,
        String model,
        String osVersion,
        String appVersion,
        Integer appVersionCode,
        String managedMode,
        String lastUpdateStatus,
        Integer lastUpdateVersionCode,
        LocalDateTime lastUpdateAt,
        String pushRegistrationId,
        String pushVendor,
        Boolean batteryOptimizationIgnored,
        Boolean notificationEnabled,
        Boolean backgroundLocationGranted,
        Boolean keepaliveGuideDone,
        LocalDateTime lastSeenAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
