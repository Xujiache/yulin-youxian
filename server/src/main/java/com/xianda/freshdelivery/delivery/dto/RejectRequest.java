package com.xianda.freshdelivery.delivery.dto;

public record RejectRequest(
        String clientEventId,
        String clientEventAt,
        GeoPointDto location,
        String reason
) {}
