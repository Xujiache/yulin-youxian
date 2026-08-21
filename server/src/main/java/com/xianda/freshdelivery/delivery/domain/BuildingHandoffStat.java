package com.xianda.freshdelivery.delivery.domain;

import java.time.LocalDateTime;

public record BuildingHandoffStat(
        Long id,
        String groupKey,
        String areaLabel,
        String buildingLabel,
        String floorBucket,
        Integer sampleCount,
        Integer avgHandoffSeconds,
        Integer p70HandoffSeconds,
        Integer p90HandoffSeconds,
        Boolean hasElevator,
        Integer accessDifficulty,
        LocalDateTime lastSampleAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
