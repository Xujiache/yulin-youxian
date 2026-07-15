package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateOrderRequest(
        @NotNull Long addressId,
        @NotNull Long deliverySlotId,
        String remark,
        @NotEmpty List<Long> cartItemIds
) {
}
