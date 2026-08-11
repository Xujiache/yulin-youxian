package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(
        @NotNull Long addressId,
        @NotNull Long deliverySlotId,
        String remark,
        List<Long> cartItemIds,
        Long productId,
        Long skuId,
        BigDecimal quantity,
        String idempotencyKey
) {
    public CreateOrderRequest(Long addressId, Long deliverySlotId, String remark, List<Long> cartItemIds) {
        this(addressId, deliverySlotId, remark, cartItemIds, null, null, null, null);
    }

    public CreateOrderRequest(
            Long addressId,
            Long deliverySlotId,
            String remark,
            List<Long> cartItemIds,
            Long productId,
            Long skuId,
            BigDecimal quantity
    ) {
        this(addressId, deliverySlotId, remark, cartItemIds, productId, skuId, quantity, null);
    }

    public CreateOrderRequest withIdempotencyKey(String nextIdempotencyKey) {
        return new CreateOrderRequest(
                addressId,
                deliverySlotId,
                remark,
                cartItemIds,
                productId,
                skuId,
                quantity,
                nextIdempotencyKey
        );
    }

    @AssertTrue(message = "请选择购物车商品或立即购买商品")
    public boolean isOrderSourceValid() {
        boolean cartCheckout = cartItemIds != null && !cartItemIds.isEmpty();
        boolean buyNow = productId != null && quantity != null;
        return cartCheckout ^ buyNow;
    }
}
