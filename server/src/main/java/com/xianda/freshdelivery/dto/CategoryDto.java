package com.xianda.freshdelivery.dto;

public record CategoryDto(
        Long id,
        String name,
        Integer sortOrder,
        String iconUrl
) {
}
