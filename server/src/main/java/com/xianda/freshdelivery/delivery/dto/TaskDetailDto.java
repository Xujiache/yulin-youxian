package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record TaskDetailDto(
        TaskCardDto card,
        List<TaskItemDto> items,
        List<TaskEventDto> events,
        List<EvidenceDto> evidences
) {
    public record TaskItemDto(
            String productName,
            Double quantity,
            String unit,
            Double weightKg,
            Integer amount
    ) {}

    public record TaskEventDto(
            Long id,
            String eventType,
            String fromStatus,
            String toStatus,
            String operatorType,
            String operatorName,
            String reason,
            String clientEventAt,
            String createdAt
    ) {}
}
