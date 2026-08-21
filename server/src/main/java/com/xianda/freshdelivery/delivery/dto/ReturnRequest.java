package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record ReturnRequest(
        String clientEventId,
        String clientEventAt,
        GeoPointDto location,
        String reason,
        List<Long> evidenceIds
) {}
