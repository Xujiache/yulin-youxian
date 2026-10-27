package com.xianda.freshdelivery.dto;

import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import java.util.List;

/**
 * 代付页面可见的商品与支付摘要，不包含订单号、地址及订单状态。
 */
public record PaymentShareDto(
        String token,
        String merchantName,
        String deliverySlot,
        Integer productAmount,
        Integer deliveryFee,
        Integer packageFee,
        Integer payableAmount,
        String paymentExpireAt,
        List<PaymentShareItemDto> items,
        Integer discountAmount,
        List<Gift> gifts,
        DrawResult lotteryResult
) {
    public PaymentShareDto {
        items = items == null ? List.of() : List.copyOf(items);
        discountAmount = discountAmount == null ? 0 : discountAmount;
        gifts = gifts == null ? List.of() : List.copyOf(gifts);
    }
}
