package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record PickupRequest(
        String clientEventId,
        String clientEventAt,
        GeoPointDto location,
        List<Long> checkedTaskIds,
        Integer actualPackageCount
) {}
