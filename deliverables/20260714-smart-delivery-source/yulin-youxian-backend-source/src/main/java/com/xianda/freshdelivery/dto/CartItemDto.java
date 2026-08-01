package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;

public record CartItemDto(
        Long id,
        Long productId,
        String name,
        String subtitle,
        String imageUrl,
        String saleUnit,
        Integer unitPrice,
        BigDecimal quantity,
        BigDecimal minPurchaseQty,
        BigDecimal stepQty,
        BigDecimal stockQty,
        boolean selected,
        Integer amount
) {
}
