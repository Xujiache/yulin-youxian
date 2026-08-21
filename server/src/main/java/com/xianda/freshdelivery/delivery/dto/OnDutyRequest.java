package com.xianda.freshdelivery.delivery.dto;

public record OnDutyRequest(
        String deviceId,
        OnDutyChecks checks,
        GeoPointDto location
) {
    public record OnDutyChecks(
            Boolean fineLocation,
            Boolean backgroundLocation,
            Boolean notification,
            Boolean batteryOptimizationIgnored,
            Boolean keepaliveGuideDone,
            Boolean helmetConfirmed
    ) {}
}
