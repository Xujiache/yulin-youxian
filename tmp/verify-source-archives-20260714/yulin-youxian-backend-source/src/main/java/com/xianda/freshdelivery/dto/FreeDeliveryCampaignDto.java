package com.xianda.freshdelivery.dto;

public record FreeDeliveryCampaignDto(
        Long id,
        String reason,
        String startDate,
        String endDate,
        Boolean enabled
) {
}
