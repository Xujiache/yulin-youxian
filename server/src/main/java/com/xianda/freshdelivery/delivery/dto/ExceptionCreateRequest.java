package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record ExceptionCreateRequest(
        String clientEventId,
        String clientEventAt,
        Long taskId,
        String exceptionType,
        String description,
        List<Long> evidenceIds,
        GeoPointDto location
) {}
