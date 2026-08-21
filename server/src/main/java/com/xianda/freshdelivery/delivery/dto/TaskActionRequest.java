package com.xianda.freshdelivery.delivery.dto;

public record TaskActionRequest(
        String clientEventId,
        String clientEventAt,
        GeoPointDto location
) {}
