package com.xianda.freshdelivery.delivery.dto;

public record TransferRequest(
        String clientEventId,
        String clientEventAt,
        GeoPointDto location,
        String reason
) {}
