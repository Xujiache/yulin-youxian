package com.xianda.freshdelivery.dto;

/**
 * 代付页面可见的最小信息集。该对象刻意不包含订单号、商品、地址及订单状态。
 */
public record PaymentShareDto(
        String token,
        String merchantName,
        Integer payableAmount,
        String paymentExpireAt
) {
}
