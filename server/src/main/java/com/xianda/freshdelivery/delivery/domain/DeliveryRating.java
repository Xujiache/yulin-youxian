package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliveryRating(
        Long id,
        Long taskId,
        Long orderId,
        Long riderId,
        Long userId,
        Integer star,
        String tags,
        String comment,
        Boolean isNegative,
        Boolean waived,
        String waiveReason,
        LocalDateTime createdAt
) {}
