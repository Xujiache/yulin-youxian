package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

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
        @NotNull Integer status
) {
}
