package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductDto(
        Long id,
        Long categoryId,
        String name,
        String subtitle,
        String imageUrl,
        String saleUnit,
        Integer unitPrice,
        BigDecimal minPurchaseQty,
        BigDecimal stepQty,
        BigDecimal stockQty,
        String badge,
        Integer status,
        Boolean recommended,
        Integer sortOrder,
        Boolean skuEnabled,
        Integer minUnitPrice,
        Integer maxUnitPrice,
        Integer availableSkuCount,
        List<ProductSpecGroupDto> specGroups,
        List<ProductSkuDto> skus
) {
    public ProductDto {
        specGroups = specGroups == null ? List.of() : List.copyOf(specGroups);
        skus = skus == null ? List.of() : List.copyOf(skus);
    }

    public ProductDto(
            Long id,
            Long categoryId,
            String name,
            String subtitle,
            String imageUrl,
            String saleUnit,
            Integer unitPrice,
            BigDecimal minPurchaseQty,
            BigDecimal stepQty,
            BigDecimal stockQty,
            String badge,
            Integer status,
            Boolean recommended,
            Integer sortOrder
    ) {
        this(
                id,
                categoryId,
                name,
                subtitle,
                imageUrl,
                saleUnit,
                unitPrice,
                minPurchaseQty,
                stepQty,
                stockQty,
                badge,
                status,
                recommended,
                sortOrder,
                false,
                unitPrice,
                unitPrice,
                status != null && status == 1 && stockQty != null && stockQty.compareTo(BigDecimal.ZERO) > 0 ? 1 : 0,
                List.of(),
                List.of()
        );
    }
}
