package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ProductSpecOptionDto(
        @NotBlank String id,
        @NotBlank String name,
        String imageUrl,
        @Min(0) Integer sortOrder
) {
}
