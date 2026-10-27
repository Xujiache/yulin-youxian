package com.xianda.freshdelivery.delivery.dto;

public record DeviceReportRequest(
        String deviceId,
        String manufacturer,
        String model,
        String osVersion,
        String appVersion,
        String pushRegistrationId,
        String pushVendor,
        Boolean batteryOptimizationIgnored,
        Boolean notificationEnabled,
        Boolean backgroundLocationGranted,
        Boolean keepaliveGuideDone,
        Integer appVersionCode,
        String managedMode,
        String lastUpdateStatus,
        Integer lastUpdateVersionCode
) {}
