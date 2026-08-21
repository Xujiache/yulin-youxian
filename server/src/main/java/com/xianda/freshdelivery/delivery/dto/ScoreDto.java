package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record ScoreDto(
        Integer score,
        String level,
        String levelName,
        Integer nextLevelScore,
        List<ScoreEventDto> recentEvents
) {}
