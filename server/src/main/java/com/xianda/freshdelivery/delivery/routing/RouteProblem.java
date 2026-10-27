package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;
import java.util.List;

public record RouteProblem(
        GeoPoint origin,
        LocalDateTime departureAt,
        List<RouteStop> stops,
        ObjectiveWeights weights,
        long solveTimeoutMillis
) {
    public RouteProblem {
        stops = List.copyOf(stops);
    }

    public int stopCount() {
        return stops.size();
    }

    public List<GeoPoint> allPoints() {
        List<GeoPoint> points = new java.util.ArrayList<>(stops.size() + 1);
        points.add(origin);
        for (RouteStop stop : stops) {
            points.add(stop.location());
        }
        return points;
    }
}
