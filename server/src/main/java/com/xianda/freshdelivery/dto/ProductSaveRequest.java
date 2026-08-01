package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;

public record ProductSaveRequest(
        @NotNull Long categoryId,
        @NotBlank String name,
        String subtitle,
        @NotBlank String imageUrl,
        @NotBlank String saleUnit,
        @NotNull @Min(1) Integer unitPrice,
        @NotNull @DecimalMin("0.001") BigDecimal minPurchaseQty,
        @NotNull @DecimalMin("0.001") BigDecimal stepQty,
        @NotNull @DecimalMin("0") BigDecimal stockQty,
        String badge,
        @NotNull Integer status,
        Boolean recommended,
        @Min(0) Integer sortOrder,
        Boolean skuEnabled,
        @Size(max = 3) List<@Valid ProductSpecGroupDto> specGroups,
        @Size(max = 200) List<@Valid ProductSkuDto> skus
) {
}
