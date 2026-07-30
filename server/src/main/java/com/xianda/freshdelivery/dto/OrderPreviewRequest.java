package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record OrderPreviewRequest(
        @NotNull Long addressId,
        @NotNull Long deliverySlotId,
        List<Long> cartItemIds,
        String remark,
        Long productId,
        Long skuId,
        BigDecimal quantity
) {
    public OrderPreviewRequest(Long addressId, Long deliverySlotId, List<Long> cartItemIds, String remark) {
        this(addressId, deliverySlotId, cartItemIds, remark, null, null, null);
    }

    @AssertTrue(message = "请选择购物车商品或立即购买商品")
    public boolean isOrderSourceValid() {
        boolean cartCheckout = cartItemIds != null && !cartItemIds.isEmpty();
        boolean buyNow = productId != null && quantity != null;
        return cartCheckout ^ buyNow;
    }
}
