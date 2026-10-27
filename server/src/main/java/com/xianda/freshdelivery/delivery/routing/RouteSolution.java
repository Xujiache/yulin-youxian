package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;
import java.util.List;

public record RouteSolution(
        String optimizerName,
        String matrixProvider,
        List<RouteLeg> legs,
        int totalDistanceMeters,
        int totalDurationSeconds,
        double objectiveValue,
        int solveMillis,
        int evaluatedNodes
) {
    public RouteSolution {
        legs = List.copyOf(legs);
    }

    public List<Long> taskIdSequence() {
        return legs.stream().map(RouteLeg::taskId).toList();
    }

    public record RouteLeg(
            long taskId,
            int seqNo,
            int legDistanceMeters,
            int legDurationSeconds,
            int handoffSeconds,
            LocalDateTime arriveAt,
            LocalDateTime departAt,
            int lateSeconds,
            int coldOverExposureSeconds
    ) {
    }
}
