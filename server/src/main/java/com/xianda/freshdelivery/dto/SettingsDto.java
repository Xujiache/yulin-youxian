package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SettingsDto(
        @NotBlank String storeName,
        String logoUrl,
        @NotNull @Min(0) Integer minOrderAmount,
        @NotNull @Min(0) Integer deliveryFee,
        @NotNull @Min(0) Integer packageFee,
        @NotBlank String businessHours,
        @NotBlank String contactPhone,
        Boolean firstOrderFreeDelivery,
        List<FreeDeliveryCampaignDto> freeDeliveryCampaigns
) {
}
