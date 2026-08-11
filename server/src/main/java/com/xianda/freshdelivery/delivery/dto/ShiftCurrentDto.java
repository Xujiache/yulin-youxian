package com.xianda.freshdelivery.delivery.dto;

public record ShiftCurrentDto(
        Long shiftId,
        Boolean onDuty,
        String onDutyAt,
        Integer onlineSeconds,
        Integer continuousSeconds,
        Integer restTotalSeconds,
        Integer taskCount,
        Integer deliveredCount,
        Integer onTimeCount,
        Integer mileageMeters,
        Integer earningAmount,
        FatigueDto fatigue
) {}
