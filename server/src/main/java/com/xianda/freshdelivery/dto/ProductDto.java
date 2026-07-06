package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;

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
        Integer status
) {
}
