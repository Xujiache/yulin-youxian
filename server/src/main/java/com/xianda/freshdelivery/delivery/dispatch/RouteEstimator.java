package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RouteEstimator {
    private final RoutePlanningPort routePlanningPort;
    private final DispatchSettings settings;

    public RouteEstimator(RoutePlanningPort routePlanningPort, DispatchSettings settings) {
        this.routePlanningPort = routePlanningPort;
        this.settings = settings;
    }

    public RouteEvaluation evaluate(GeoPoint origin, LocalDateTime now, LocalDateTime departAt,
                                    List<DispatchTaskRow> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return RouteEvaluation.EMPTY;
        }
        Map<Long, DispatchTaskRow> byId = new LinkedHashMap<>();
        List<Long> unlocated = new ArrayList<>();
        List<RoutePlanningPort.RouteStopInput> stops = new ArrayList<>(tasks.size());
        for (DispatchTaskRow task : tasks) {
            byId.put(task.taskId(), task);
            if (!task.located()) {
                unlocated.add(task.taskId());
                continue;
            }
            stops.add(new RoutePlanningPort.RouteStopInput(
                    task.taskId(),
                    task.location(),
                    task.coldChain().name(),
                    task.dueAt(),
                    handoffOf(task)));
        }
        if (stops.isEmpty()) {
            return new RouteEvaluation(0, 0, Map.of(), null, List.of(), 0d, List.of(), List.copyOf(unlocated));
        }

        RoutePlanningPort.RoutePlanEstimate estimate = routePlanningPort.estimateRoute(origin, stops);
        List<RoutePlanningPort.RouteLegEstimate> legs = new ArrayList<>(estimate.legs());
        legs.sort(Comparator.comparingInt(RoutePlanningPort.RouteLegEstimate::seqNo));

        Map<Long, LocalDateTime> arrivals = new LinkedHashMap<>();
        List<Long> overtime = new ArrayList<>();
        List<Long> coldRisk = new ArrayList<>();
        Long minSlack = null;
        double maxColdRatio = 0d;
        LocalDateTime cursor = departAt;

        for (RoutePlanningPort.RouteLegEstimate leg : legs) {
            DispatchTaskRow task = leg.taskId() == null ? null : byId.get(leg.taskId());
            cursor = cursor.plusSeconds(Math.max(0, leg.legDurationSeconds()));
            if (task == null) {
                continue;
            }
            LocalDateTime arriveAt = cursor.plusSeconds(Math.max(0, task.extraTimeSeconds()));
            arrivals.put(task.taskId(), arriveAt);
            cursor = arriveAt.plusSeconds(handoffOf(task));

            LocalDateTime dueAt = task.dueAt();
            if (dueAt != null) {
                long slack = Duration.between(arriveAt, dueAt).getSeconds();
                minSlack = minSlack == null ? slack : Math.min(minSlack, slack);
                if (slack < 0L) {
                    overtime.add(task.taskId());
                }
            }
            Integer maxExposure = task.coldChain().maxExposureSeconds();
            if (task.coldChain() != ColdChainLevel.NORMAL && maxExposure != null && maxExposure > 0) {
                long exposure = Math.max(0L, Duration.between(now, arriveAt).getSeconds());
                double ratio = (double) exposure / maxExposure;
                maxColdRatio = Math.max(maxColdRatio, ratio);
                if (ratio > 1d) {
                    coldRisk.add(task.taskId());
                }
            }
        }

        return new RouteEvaluation(
                Math.max(0, estimate.totalDistanceMeters()),
                Math.max(0, estimate.totalDurationSeconds()),
                Map.copyOf(arrivals),
                minSlack,
                List.copyOf(overtime),
                maxColdRatio,
                List.copyOf(coldRisk),
                List.copyOf(unlocated));
    }

    public int handoffOf(DispatchTaskRow task) {
        Integer handoff = task.handoffSeconds();
        return handoff == null || handoff <= 0 ? settings.defaultHandoffSeconds() : handoff;
    }

    public GeoPoint storeOrigin() {
        return settings.storeOrigin();
    }
}
