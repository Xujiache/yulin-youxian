package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeliverySlotSaveRequest(
        @NotBlank String label,
        @NotNull @Min(1) Integer maxOrders,
        boolean available
) {
}
