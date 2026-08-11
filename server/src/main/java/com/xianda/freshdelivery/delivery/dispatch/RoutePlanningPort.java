package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;
import java.util.List;

public interface RoutePlanningPort {

    RoutePlanEstimate estimateRoute(GeoPoint origin, List<RouteStopInput> stops);

    record RouteStopInput(
            Long taskId,
            GeoPoint location,
            String coldChainLevel,
            LocalDateTime windowEndAt,
            Integer handoffSeconds
    ) {
    }

    record RouteLegEstimate(
            Long taskId,
            int seqNo,
            int legDistanceMeters,
            int legDurationSeconds
    ) {
    }

    record RoutePlanEstimate(
            int totalDistanceMeters,
            int totalDurationSeconds,
            double objectiveValue,
            List<RouteLegEstimate> legs
    ) {
    }
}
