package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;

/**
 * 代付页面可见的商品摘要，不包含商品 ID、订单 ID 或订单状态。
 */
public record PaymentShareItemDto(
        String productName,
        String imageUrl,
        String saleUnit,
        Integer unitPrice,
        BigDecimal quantity,
        Integer amount,
        String specificationText
) {
}
