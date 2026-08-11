package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record AppealCreateRequest(
        String targetType,
        Long targetId,
        String reason,
        List<Long> evidenceIds
) {}
