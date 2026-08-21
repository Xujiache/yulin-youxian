package com.xianda.freshdelivery.delivery.dto;

public record PickReadyResponse(
        Long taskId,
        String taskNo,
        String status,
        String holdUntilAt,
        DispatchPreviewDto dispatchPreview
) {
    public record DispatchPreviewDto(
            Long recommendedRiderId,
            String recommendedRiderName,
            Double score,
            String reason
    ) {}
}
