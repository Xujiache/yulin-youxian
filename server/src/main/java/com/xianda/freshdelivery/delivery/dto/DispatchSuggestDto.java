package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record DispatchSuggestDto(
        Long taskId,
        List<CandidateDto> candidates,
        Long recommendedRiderId,
        BatchingHintDto batchingHint
) {
    public record CandidateDto(
            Long riderId,
            String riderName,
            Double score,
            Integer addedDistanceMeters,
            Integer addedDurationSeconds,
            String estimatedArriveAt,
            String overtimeRiskAfter,
            ScoreBreakdownDto breakdown,
            List<String> blockers
    ) {}

    public record ScoreBreakdownDto(
            Double addedDistance,
            Double overtimeRisk,
            Double loadBalance,
            Double coldChain,
            Double riderLevel
    ) {}

    public record BatchingHintDto(
            List<Long> mergeWithTaskIds,
            String reason
    ) {}
}
