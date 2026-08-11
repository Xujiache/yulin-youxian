package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RouteSolverService {
    public static final String FIXED_SEQUENCE = "FIXED_SEQUENCE";

    private static final Logger log = LoggerFactory.getLogger(RouteSolverService.class);

    private final DistanceMatrixProviderFactory matrixProviderFactory;
    private final MatrixCacheService matrixCacheService;
    private final RouteOptimizerFactory optimizerFactory;
    private final RoutingSettings settings;

    public RouteSolverService(DistanceMatrixProviderFactory matrixProviderFactory,
                              MatrixCacheService matrixCacheService,
                              RouteOptimizerFactory optimizerFactory,
                              RoutingSettings settings) {
        this.matrixProviderFactory = matrixProviderFactory;
        this.matrixCacheService = matrixCacheService;
        this.optimizerFactory = optimizerFactory;
        this.settings = settings;
    }

    public RouteSolution solve(GeoPoint origin, LocalDateTime departureAt, List<RouteStop> stops) {
        long startedAt = System.nanoTime();
        long totalBudgetMillis = Math.max(1L, settings.solveTimeoutMillis());
        if (origin == null || origin.lat() == null || origin.lng() == null) {
            throw new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, "门店坐标未配置，无法规划路径");
        }
        List<RouteStop> positioned = new ArrayList<>();
        List<RouteStop> unpositioned = new ArrayList<>();
        for (RouteStop stop : stops) {
            if (hasCoordinate(stop)) {
                positioned.add(stop);
            } else {
                unpositioned.add(stop);
            }
        }
        if (!unpositioned.isEmpty()) {
            log.warn("{} 个站点缺少经纬度，已排到序列末尾并保留派送", unpositioned.size());
            unpositioned.sort(Comparator.comparingLong(RouteStop::taskId));
        }

        RouteProblem problem = new RouteProblem(origin, departureAt, positioned,
                settings.objectiveWeights(), totalBudgetMillis);
        TravelMatrix matrix = buildMatrix(problem);
        RouteProblem solveProblem = new RouteProblem(origin, departureAt, positioned,
                problem.weights(), remainingBudgetMillis(startedAt, totalBudgetMillis));
        RouteOptimizer optimizer = optimizerFactory.resolve(solveProblem, matrix);
        RouteSolution solution = optimizer.solve(solveProblem, matrix);
        return unpositioned.isEmpty() ? solution : appendUnpositioned(solution, unpositioned, departureAt);
    }

    public RouteSolution evaluateSequence(GeoPoint origin, LocalDateTime departureAt, List<RouteStop> stops) {
        long totalBudgetMillis = Math.max(1L, settings.solveTimeoutMillis());
        if (origin == null || origin.lat() == null || origin.lng() == null) {
            throw new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, "门店坐标未配置，无法计算路径");
        }
        List<RouteStop> positioned = new ArrayList<>();
        List<RouteStop> unpositioned = new ArrayList<>();
        for (RouteStop stop : stops) {
            if (hasCoordinate(stop)) {
                positioned.add(stop);
            } else {
                unpositioned.add(stop);
            }
        }
        RouteProblem problem = new RouteProblem(origin, departureAt, positioned,
                settings.objectiveWeights(), totalBudgetMillis);
        TravelMatrix matrix = buildMatrix(problem);
        RouteObjectiveEvaluator evaluator = new RouteObjectiveEvaluator(problem, matrix);
        int[] order = new int[positioned.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        RouteObjectiveEvaluator.Evaluation evaluation = evaluator.evaluate(order);
        RouteSolution solution = new RouteSolution(FIXED_SEQUENCE, matrix.providerName(), evaluation.legs(),
                evaluation.totalDistanceMeters(), evaluation.totalDurationSeconds(), evaluation.objective(), 0, 0);
        return unpositioned.isEmpty() ? solution : appendUnpositioned(solution, unpositioned, departureAt);
    }

    public TravelMatrix buildMatrix(RouteProblem problem) {
        DistanceMatrixProvider provider = matrixProviderFactory.resolve();
        List<GeoPoint> points = problem.allPoints();
        try {
            return matrixCacheService.buildMatrix(points, TravelMode.EBIKE, provider);
        } catch (DistanceMatrixException ex) {
            log.warn("矩阵提供方 {} 调用失败，回落 {}", provider.name(), HaversineMatrixProvider.NAME, ex);
            return matrixCacheService.buildMatrix(points, TravelMode.EBIKE, matrixProviderFactory.fallback());
        }
    }

    private RouteSolution appendUnpositioned(RouteSolution solution, List<RouteStop> unpositioned,
                                             LocalDateTime departureAt) {
        List<RouteSolution.RouteLeg> legs = new ArrayList<>(solution.legs());
        int seq = legs.size();
        LocalDateTime cursor = legs.isEmpty() ? departureAt : legs.get(legs.size() - 1).departAt();
        int elapsed = solution.totalDurationSeconds();
        for (RouteStop stop : unpositioned) {
            seq++;
            int handoff = Math.max(0, stop.handoffSeconds());
            LocalDateTime arriveAt = cursor;
            int waitSeconds = 0;
            if (arriveAt != null && stop.windowStartAt() != null && arriveAt.isBefore(stop.windowStartAt())) {
                waitSeconds = (int) Math.min(Integer.MAX_VALUE,
                        java.time.Duration.between(arriveAt, stop.windowStartAt()).getSeconds());
                arriveAt = stop.windowStartAt();
            }
            LocalDateTime departAt = arriveAt == null ? null : arriveAt.plusSeconds(handoff);
            legs.add(new RouteSolution.RouteLeg(stop.taskId(), seq, 0, 0, handoff, arriveAt, departAt, 0, 0));
            cursor = departAt;
            elapsed += waitSeconds + handoff;
        }
        return new RouteSolution(solution.optimizerName(), solution.matrixProvider(), legs,
                solution.totalDistanceMeters(), elapsed, solution.objectiveValue(),
                solution.solveMillis(), solution.evaluatedNodes());
    }

    private static boolean hasCoordinate(RouteStop stop) {
        GeoPoint location = stop.location();
        return location != null && location.lat() != null && location.lng() != null
                && !(location.lat() == 0d && location.lng() == 0d);
    }

    private static long remainingBudgetMillis(long startedAtNanos, long totalBudgetMillis) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
        long elapsedMillis = (elapsedNanos + 999_999L) / 1_000_000L;
        return Math.max(1L, totalBudgetMillis - elapsedMillis);
    }
}
