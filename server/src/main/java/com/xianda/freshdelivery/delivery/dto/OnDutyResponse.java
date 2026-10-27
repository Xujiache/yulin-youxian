package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record OnDutyResponse(
        Long shiftId,
        String onDutyAt,
        List<String> warnings
) {}
