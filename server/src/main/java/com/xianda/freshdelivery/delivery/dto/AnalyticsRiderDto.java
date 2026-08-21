package com.xianda.freshdelivery.delivery.dto;

public record AnalyticsRiderDto(
        Long riderId,
        String riderName,
        Integer taskCount,
        Integer deliveredCount,
        Integer returnedCount,
        Double onTimeRate,
        Integer avgDeliveryMinutes,
        Integer avgHandoffSeconds,
        Integer totalDistanceMeters,
        Integer activeDays,
        Double tasksPerDay
) {}
