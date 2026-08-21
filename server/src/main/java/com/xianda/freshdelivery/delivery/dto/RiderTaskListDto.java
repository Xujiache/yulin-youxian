package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record RiderTaskListDto(
        List<WaveGroupDto> waves,
        List<TaskCardDto> standaloneTasks
) {
    public record WaveGroupDto(
            Long waveId,
            String waveNo,
            String status,
            Integer taskCount,
            Integer completedCount,
            Integer planDistanceMeters,
            Integer planDurationSeconds,
            String planReturnAt,
            String maxColdChainLevel,
            List<TaskCardDto> stops
    ) {}
}
