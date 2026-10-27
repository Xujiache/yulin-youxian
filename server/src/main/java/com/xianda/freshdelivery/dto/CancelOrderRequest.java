package com.xianda.freshdelivery.dto;

public record CancelOrderRequest(
        Boolean returnToCart
) {
    public boolean shouldReturnToCart() {
        return Boolean.TRUE.equals(returnToCart);
    }
}
