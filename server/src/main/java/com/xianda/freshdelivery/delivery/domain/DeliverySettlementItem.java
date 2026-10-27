package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record DeliverySettlementItem(
        Long id,
        Long settlementId,
        Long riderId,
        Long taskId,
        String taskNo,
        String itemType,
        Integer amount,
        String calcDetail,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {}
