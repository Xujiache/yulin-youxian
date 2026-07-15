package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAddressRequest(
        @NotBlank String name,
        @NotBlank String phone,
        @NotBlank String detail,
        String locationName,
        Double latitude,
        Double longitude,
        boolean isDefault
) {
}
