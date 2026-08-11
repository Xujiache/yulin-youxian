package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record DeliverRequest(
        String clientEventId,
        String clientEventAt,
        GeoPointDto location,
        String verifyCode,
        List<Long> evidenceIds,
        String receiveMethod
) {}
