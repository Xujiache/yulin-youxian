package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;

public record OrderItemDto(
        Long id,
        Long productId,
        String productName,
        String imageUrl,
        String saleUnit,
        Integer unitPrice,
        BigDecimal quantity,
        Integer amount
) {
}
