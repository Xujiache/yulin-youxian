package com.xianda.freshdelivery.delivery.dto;

public record RiderAppCoverageDto(
        String channel,
        Integer currentVersionCode,
        String currentVersionName,
        long activeDevices,
        long onLatest,
        long behind,
        long unknownVersion,
        long deviceOwner,
        long profileOwner,
        long standard
) {
}
