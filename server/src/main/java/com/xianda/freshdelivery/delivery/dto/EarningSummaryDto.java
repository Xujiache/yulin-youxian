package com.xianda.freshdelivery.delivery.dto;

public record EarningSummaryDto(
        String period,
        Integer taskCount,
        Integer onTimeCount,
        Integer totalAmount,
        Integer baseAmount,
        Integer distanceAmount,
        Integer weightAmount,
        Integer floorAmount,
        Integer weatherAmount,
        Integer nightAmount,
        Integer holidayAmount,
        Integer bonusAmount,
        Integer adjustAmount
) {}
