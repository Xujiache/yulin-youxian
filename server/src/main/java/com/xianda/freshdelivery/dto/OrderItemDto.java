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
        Integer amount,
        Long skuId,
        String skuCode,
        String specificationText
) {
    public OrderItemDto(
            Long id,
            Long productId,
            String productName,
            String imageUrl,
            String saleUnit,
            Integer unitPrice,
            BigDecimal quantity,
            Integer amount
    ) {
        this(id, productId, productName, imageUrl, saleUnit, unitPrice, quantity, amount, null, "", "");
    }
}
