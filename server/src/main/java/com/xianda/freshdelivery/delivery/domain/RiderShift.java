package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record RiderShift(
        Long id,
        Long riderId,
        LocalDate shiftDate,
        LocalDateTime onDutyAt,
        LocalDateTime offDutyAt,
        String offDutyReason,
        Integer onlineSeconds,
        Integer continuousSeconds,
        Integer restTotalSeconds,
        LocalDateTime lastRestAt,
        LocalDateTime fatigue4hNotifiedAt,
        LocalDateTime fatigue8hConfirmedAt,
        LocalDateTime dispatchPausedUntil,
        Integer taskCount,
        Integer deliveredCount,
        Integer onTimeCount,
        Integer exceptionCount,
        Integer mileageMeters,
        Integer earningAmount,
        Boolean helmetConfirmed,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
