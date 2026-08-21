package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record SettlementGenerateRequest(
        String periodType,
        String periodStart,
        String periodEnd,
        List<Long> riderIds
) {}
