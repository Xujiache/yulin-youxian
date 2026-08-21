package com.xianda.freshdelivery.delivery.dto;

public record DeliveryTaskBriefDto(
        Long taskId,
        String taskNo,
        String status,
        String statusText,
        Long riderId,
        String riderName,
        String waveNo,
        String etaAt,
        String overtimeRisk,
        Boolean farDelivery
) {}
