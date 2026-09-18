package com.xianda.freshdelivery.dto;

import java.util.List;

public record RefundableItemsDto(
        Integer availableAmount, boolean hasUnallocatedRefunds, List<Item> items
) {
    public record Item(OrderItemDto item, Integer paidAmount, Integer availableAmount) {}
}
