package com.xianda.freshdelivery.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProductSpecGroupDto(
        @NotBlank String id,
        @NotBlank String name,
        @Min(0) Integer sortOrder,
        @NotEmpty @Size(max = 20) List<@Valid ProductSpecOptionDto> options
) {
}
