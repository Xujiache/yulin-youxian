package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BannerDto(
        Long id,
        @NotBlank String title,
        String subtitle,
        @NotBlank String imageUrl,
        String linkType,
        String linkTarget,
        @NotNull @Min(0) Integer sortOrder,
        @NotNull Boolean enabled
) {
}
