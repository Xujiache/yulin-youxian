package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CartItemRequest(
        @NotNull Long productId,
        @NotNull @DecimalMin("0.001") BigDecimal quantity
) {
}
