package com.xianda.freshdelivery.dto;

/**
 * 仅供代付人调起微信收银台的参数，不暴露关联订单标识与状态。
 */
public record SharedPaymentDto(
        String timeStamp,
        String nonceStr,
        String packageValue,
        String signType,
        String paySign
) {
}
