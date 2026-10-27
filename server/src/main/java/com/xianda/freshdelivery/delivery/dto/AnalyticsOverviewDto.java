package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record AnalyticsOverviewDto(
        Integer taskCount,
        Integer deliveredCount,
        Double onTimeRate,
        Integer avgDeliveryMinutes,
        Double tasksPerRiderPerDay,
        List<TypeCountDto> exceptionDistribution,
        List<BuildingDifficultyDto> buildingDifficultyTop,
        List<HourCountDto> hourlyTaskCounts
) {
    public record TypeCountDto(
            String type,
            Integer count
    ) {}

    public record BuildingDifficultyDto(
            String groupKey,
            String areaLabel,
            String buildingLabel,
            Integer avgHandoffSeconds,
            Integer accessDifficulty,
            Integer sampleCount
    ) {}

    public record HourCountDto(
            Integer hour,
            Integer count
    ) {}
}
