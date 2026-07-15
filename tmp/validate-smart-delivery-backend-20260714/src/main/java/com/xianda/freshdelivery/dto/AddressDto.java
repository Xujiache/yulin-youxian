package com.xianda.freshdelivery.dto;

public record AddressDto(
        Long id,
        String name,
        String phone,
        String detail,
        String locationName,
        Double latitude,
        Double longitude,
        boolean isDefault
) {
}
