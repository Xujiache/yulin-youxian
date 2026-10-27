package com.xianda.freshdelivery.delivery.dto;

public record ShiftHistoryItemDto(
        Long shiftId,
        String shiftDate,
        String onDutyAt,
        String offDutyAt,
        String offDutyReason,
        Integer onlineSeconds,
        Integer restTotalSeconds,
        Integer taskCount,
        Integer deliveredCount,
        Integer onTimeCount,
        Integer exceptionCount,
        Integer mileageMeters,
        Integer earningAmount
) {}
