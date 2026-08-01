package com.xianda.freshdelivery.dto;

import java.util.List;

public record CartDto(
        List<CartItemDto> items,
        Integer selectedCount,
        Integer totalAmount
) {
}
