package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record ProductSkuDto(
        Long id,
        String skuCode,
        String barcode,
        @NotEmpty List<@NotBlank String> optionValueIds,
        String specificationText,
        String imageUrl,
        @NotNull @Min(1) Integer unitPrice,
        @NotNull @DecimalMin("0") BigDecimal stockQty,
        @NotBlank String saleUnit,
        @NotNull @DecimalMin("0.001") BigDecimal minPurchaseQty,
        @NotNull @DecimalMin("0.001") BigDecimal stepQty,
        @NotNull Integer status,
        Boolean defaultSku,
        @Min(0) Integer sortOrder
) {
    public ProductSkuDto {
        optionValueIds = optionValueIds == null ? List.of() : List.copyOf(optionValueIds);
    }
}
