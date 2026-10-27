package com.xianda.freshdelivery.delivery.dto;

public record EarningItemDto(
        Long id,
        Long taskId,
        String taskNo,
        String itemType,
        Integer amount,
        String calcDetail,
        String occurredAt
) {}
