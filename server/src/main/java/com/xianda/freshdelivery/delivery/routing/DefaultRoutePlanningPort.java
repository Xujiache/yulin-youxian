package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.dispatch.RoutePlanningPort;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class DefaultRoutePlanningPort implements RoutePlanningPort {
    private final RouteSolverService routeSolverService;
    private final RoutingSettings settings;
    private final Clock clock;

    @Autowired
    public DefaultRoutePlanningPort(RouteSolverService routeSolverService, RoutingSettings settings) {
        this(routeSolverService, settings, RoutingTimes.systemClock());
    }

    public DefaultRoutePlanningPort(RouteSolverService routeSolverService, RoutingSettings settings, Clock clock) {
        this.routeSolverService = routeSolverService;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public RoutePlanEstimate estimateRoute(GeoPoint origin, List<RouteStopInput> stops) {
        if (stops == null || stops.isEmpty()) {
            return new RoutePlanEstimate(0, 0, 0d, List.of());
        }
        LocalDateTime departureAt = RoutingTimes.now(clock);
        GeoPoint resolvedOrigin = origin != null ? origin : settings.storeOrigin();
        List<RouteStop> internalStops = new ArrayList<>(stops.size());
        for (RouteStopInput input : stops) {
            internalStops.add(toRouteStop(input));
        }
        RouteSolution solution = routeSolverService.solve(resolvedOrigin, departureAt, internalStops);
        List<RouteLegEstimate> legs = new ArrayList<>(solution.legs().size());
        for (RouteSolution.RouteLeg leg : solution.legs()) {
            legs.add(new RouteLegEstimate(leg.taskId(), leg.seqNo(), leg.legDistanceMeters(), leg.legDurationSeconds()));
        }
        return new RoutePlanEstimate(solution.totalDistanceMeters(), solution.totalDurationSeconds(),
                solution.objectiveValue(), legs);
    }

    private RouteStop toRouteStop(RouteStopInput input) {
        int handoff = input.handoffSeconds() == null || input.handoffSeconds() <= 0
                ? settings.defaultHandoffSeconds()
                : input.handoffSeconds();
        return new RouteStop(
                input.taskId() == null ? 0L : input.taskId(),
                input.location(),
                RouteStop.parseColdChain(input.coldChainLevel()),
                null,
                input.windowEndAt(),
                input.windowEndAt(),
                handoff,
                null,
                null);
    }
}
