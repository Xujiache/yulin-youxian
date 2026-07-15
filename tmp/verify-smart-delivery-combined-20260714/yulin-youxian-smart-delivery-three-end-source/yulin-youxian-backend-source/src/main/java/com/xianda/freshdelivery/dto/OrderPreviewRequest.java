package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record OrderPreviewRequest(
        @NotNull Long addressId,
        @NotNull Long deliverySlotId,
        @NotEmpty List<Long> cartItemIds,
        String remark
) {
}
