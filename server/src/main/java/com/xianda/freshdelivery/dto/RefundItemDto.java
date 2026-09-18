package com.xianda.freshdelivery.dto;

import java.math.BigDecimal;

/** 商品快照，数量为原购买数量，refundAmount 为本次该商品实际退款金额（分）。 */
public record RefundItemDto(
        Long orderItemId, Long productId, String productName, String imageUrl,
        String specificationText, String saleUnit, BigDecimal quantity, Integer refundAmount
) {}
