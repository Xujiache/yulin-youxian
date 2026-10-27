package com.xianda.freshdelivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeliveryWeightCheck(
        Long id,
        Long orderId,
        Long taskId,
        Long orderItemId,
        String productName,
        BigDecimal orderedQty,
        BigDecimal pickedWeightKg,
        BigDecimal customerWeightKg,
        BigDecimal tolerancePercent,
        BigDecimal diffPercent,
        Long scaleEvidenceId,
        Long customerEvidenceId,
        String verdict,
        Integer refundAmount,
        String handledBy,
        LocalDateTime handledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
