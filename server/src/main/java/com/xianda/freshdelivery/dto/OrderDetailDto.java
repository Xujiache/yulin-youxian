package com.xianda.freshdelivery.dto;

import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import java.util.List;

public record OrderDetailDto(
        Long id,
        String orderNo,
        String status,
        AddressDto address,
        String deliverySlot,
        List<OrderItemDto> items,
        Integer productAmount,
        Integer deliveryFee,
        Integer packageFee,
        Integer payableAmount,
        Integer paidAmount,
        Integer refundedAmount,
        String remark,
        String createdAt,
        String latestRefundStatus,
        String latestRefundReason,
        Long userId,
        List<RefundDto> refunds,
        String transactionId,
        String paymentOrderNo,
        String paymentExpireAt,
        boolean canRestartPayment,
        boolean requiresDeliverySlotSelection,
        Integer discountAmount,
        List<Gift> gifts,
        DrawResult lotteryResult
) {
    public OrderDetailDto {
        discountAmount = discountAmount == null ? 0 : discountAmount;
        gifts = gifts == null ? List.of() : List.copyOf(gifts);
    }

    public OrderDetailDto(
            Long id,
            String orderNo,
            String status,
            AddressDto address,
            String deliverySlot,
            List<OrderItemDto> items,
            Integer productAmount,
            Integer deliveryFee,
            Integer packageFee,
            Integer payableAmount,
            Integer paidAmount,
            Integer refundedAmount,
            String remark,
            String createdAt,
            String latestRefundStatus,
            String latestRefundReason,
            Long userId,
            List<RefundDto> refunds,
            String transactionId
    ) {
        this(
                id, orderNo, status, address, deliverySlot, items,
                productAmount, deliveryFee, packageFee, payableAmount,
                paidAmount, refundedAmount, remark, createdAt,
                latestRefundStatus, latestRefundReason, userId, refunds,
                transactionId, orderNo, "", false, false,
                0, List.of(), null
        );
    }
}
