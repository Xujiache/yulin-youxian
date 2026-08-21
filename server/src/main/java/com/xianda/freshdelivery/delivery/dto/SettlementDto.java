package com.xianda.freshdelivery.delivery.dto;

public record SettlementDto(
        Long id,
        String settlementNo,
        String periodType,
        String periodStart,
        String periodEnd,
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
        String confirmedAt,
        String paidAt,
        String remark
) {}
