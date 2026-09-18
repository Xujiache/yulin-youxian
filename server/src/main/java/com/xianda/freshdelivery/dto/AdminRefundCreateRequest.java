package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.List;

public record AdminRefundCreateRequest(
        @NotNull Long userId,
        @NotNull Long orderId,
        @NotNull @Min(1) Integer refundAmount,
        @NotBlank @Size(max = 200) String reason,
        @Valid List<@NotNull RefundItemRequest> items,
        @NotBlank @Size(max = 80) String requestKey
) {
    // 内部配送异常等整单退款沿用原入口；后台 HTTP 入口必须指定商品。
    public AdminRefundCreateRequest(Long userId, Long orderId, Integer refundAmount, String reason) {
        this(userId, orderId, refundAmount, reason, null, null);
    }

    public AdminRefundCreateRequest(Long userId, Long orderId, Integer refundAmount, String reason, List<RefundItemRequest> items) {
        this(userId, orderId, refundAmount, reason, items, null);
    }
}
