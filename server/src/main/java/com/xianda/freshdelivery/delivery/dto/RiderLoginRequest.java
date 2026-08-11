package com.xianda.freshdelivery.delivery.dto;

public record RiderLoginRequest(
        String phone,
        String password,
        String deviceId,
        DeviceInfo deviceInfo
) {
    public record DeviceInfo(
            String manufacturer,
            String model,
            String osVersion,
            String appVersion
    ) {}
}
