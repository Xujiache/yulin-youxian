package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;
import java.util.List;

public record StockOverviewItemDto(
        Long productId,
        String productName,
        String imageUrl,
        String saleUnit,
        BigDecimal quantity,
        Integer orderCount,
        Integer amount,
        List<String> orderNos
) {
}
