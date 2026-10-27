package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DeliverySettlement(
        Long id,
        String settlementNo,
        Long riderId,
        String periodType,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer taskCount,
        Integer onTimeCount,
        Integer baseAmount,
        Integer distanceAmount,
        Integer weightAmount,
        Integer floorAmount,
        Integer weatherAmount,
        Integer nightAmount,
        Integer holidayAmount,
        Integer bonusAmount,
        Integer adjustAmount,
        Integer totalAmount,
        String status,
        LocalDateTime confirmedAt,
        LocalDateTime paidAt,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
