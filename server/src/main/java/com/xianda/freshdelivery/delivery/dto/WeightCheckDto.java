package com.xianda.freshdelivery.delivery.dto;

public record WeightCheckDto(
        Long id,
        Long orderId,
        Long taskId,
        Long orderItemId,
        String productName,
        Double orderedQty,
        Double pickedWeightKg,
        Double customerWeightKg,
        Double tolerancePercent,
        Double diffPercent,
        Long scaleEvidenceId,
        Long customerEvidenceId,
        String verdict,
        Integer refundAmount,
        String handledBy,
        String handledAt,
        String createdAt
) {}
