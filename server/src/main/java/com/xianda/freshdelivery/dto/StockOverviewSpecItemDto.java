package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;

public record StockOverviewSpecItemDto(
        Long skuId,
        String specificationText,
        BigDecimal quantity,
        String saleUnit,
        Integer orderCount,
        Integer amount
) {
}
