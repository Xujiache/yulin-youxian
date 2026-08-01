package com.xianda.freshdelivery.dto;

public record PaymentDto(
        Long orderId,
        String orderNo,
        String status,
        String timeStamp,
        String nonceStr,
        String packageValue,
        String signType,
        String paySign,
        boolean developmentMode
) {
}
