package com.xianda.freshdelivery.delivery.routing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RouteObjectiveEvaluator {
    public static final long NO_DUE = Long.MAX_VALUE;
    public static final long NO_WINDOW_START = Long.MIN_VALUE;
    public static final long NO_EXPOSURE_LIMIT = -1L;

    private final RouteProblem problem;
    private final TravelMatrix matrix;
    private final ObjectiveWeights weights;
    private final long[] dueOffsetSeconds;
    private final long[] windowStartOffsetSeconds;
    private final long[] maxExposureSeconds;
    private final int[] handoffSeconds;
    private final List<RouteCluster> clusters;

    public RouteObjectiveEvaluator(RouteProblem problem, TravelMatrix matrix) {
        int n = problem.stopCount();
        if (matrix.size() < n + 1) {
            throw new IllegalArgumentException("矩阵维度 " + matrix.size() + " 不足以覆盖 " + n + " 个站点与起点");
        }
        this.problem = problem;
        this.matrix = matrix;
        this.weights = problem.weights() == null ? ObjectiveWeights.defaults() : problem.weights();
        this.dueOffsetSeconds = new long[n];
        this.windowStartOffsetSeconds = new long[n];
        this.maxExposureSeconds = new long[n];
        this.handoffSeconds = new int[n];
        LocalDateTime departure = problem.departureAt();
        for (int i = 0; i < n; i++) {
            RouteStop stop = problem.stops().get(i);
            dueOffsetSeconds[i] = offsetOf(departure, stop.dueAt() != null ? stop.dueAt() : stop.windowEndAt(), NO_DUE);
            windowStartOffsetSeconds[i] = offsetOf(departure, stop.windowStartAt(), NO_WINDOW_START);
            Integer limit = stop.maxExposureSeconds();
            maxExposureSeconds[i] = limit == null ? NO_EXPOSURE_LIMIT : limit.longValue();
            handoffSeconds[i] = Math.max(0, stop.handoffSeconds());
        }
        this.clusters = buildClusters(problem);
    }

    public RouteProblem problem() {
        return problem;
    }

    public ObjectiveWeights weights() {
        return weights;
    }

    public List<RouteCluster> clusters() {
        return clusters;
    }

    public int clusterCount() {
        return clusters.size();
    }

    public PartialState initial() {
        return new PartialState(0, 0L, 0L, 0d);
    }

    public PartialState extend(PartialState state, int stopIndex) {
        int target = stopIndex + 1;
        long travelSeconds = matrix.durationSeconds(state.node(), target);
        long travelMeters = matrix.distanceMeters(state.node(), target);
        long rawArrival = state.elapsedSeconds() + travelSeconds;
        long windowStart = windowStartOffsetSeconds[stopIndex];
        long waitSeconds = windowStart == NO_WINDOW_START
                ? 0L
                : Math.max(0L, windowStart - rawArrival);
        long arrival = rawArrival + waitSeconds;
        double objective = state.objective() + travelSeconds + waitSeconds;

        long due = dueOffsetSeconds[stopIndex];
        if (due != NO_DUE) {
            long late = arrival - due;
            if (late > 0L) {
                objective += weights.late() * (double) late * (double) late;
            }
        }
        long exposureLimit = maxExposureSeconds[stopIndex];
        if (exposureLimit != NO_EXPOSURE_LIMIT) {
            long over = arrival - exposureLimit;
            if (over > 0L) {
                objective += weights.cold() * (double) over * (double) over;
            }
        }
        if (waitSeconds > 0L) {
            objective += weights.early() * (double) waitSeconds;
        }
        return new PartialState(target, arrival + handoffSeconds[stopIndex],
                state.distanceMeters() + travelMeters, objective);
    }

    public PartialState extendCluster(PartialState state, RouteCluster cluster) {
        PartialState current = state;
        for (int stopIndex : cluster.stopIndexes()) {
            current = extend(current, stopIndex);
        }
        return current;
    }

    public double objectiveOfClusterOrder(int[] clusterOrder) {
        PartialState state = initial();
        for (int clusterIndex : clusterOrder) {
            state = extendCluster(state, clusters.get(clusterIndex));
        }
        return state.objective();
    }

    public Evaluation evaluateClusterOrder(int[] clusterOrder) {
        int[] stopOrder = flatten(clusterOrder);
        return evaluate(stopOrder);
    }

    public int[] flatten(int[] clusterOrder) {
        int[] stopOrder = new int[problem.stopCount()];
        int cursor = 0;
        for (int clusterIndex : clusterOrder) {
            for (int stopIndex : clusters.get(clusterIndex).stopIndexes()) {
                stopOrder[cursor++] = stopIndex;
            }
        }
        return stopOrder;
    }

    public Evaluation evaluate(int[] stopOrder) {
        PartialState state = initial();
        List<RouteSolution.RouteLeg> legs = new ArrayList<>(stopOrder.length);
        LocalDateTime departure = problem.departureAt();
        for (int seq = 0; seq < stopOrder.length; seq++) {
            int stopIndex = stopOrder[seq];
            PartialState previous = state;
            state = extend(state, stopIndex);
            long arrival = state.elapsedSeconds() - handoffSeconds[stopIndex];
            long due = dueOffsetSeconds[stopIndex];
            long exposureLimit = maxExposureSeconds[stopIndex];
            RouteStop stop = problem.stops().get(stopIndex);
            legs.add(new RouteSolution.RouteLeg(
                    stop.taskId(),
                    seq + 1,
                    (int) (state.distanceMeters() - previous.distanceMeters()),
                    matrix.durationSeconds(previous.node(), stopIndex + 1),
                    handoffSeconds[stopIndex],
                    departure == null ? null : departure.plusSeconds(arrival),
                    departure == null ? null : departure.plusSeconds(state.elapsedSeconds()),
                    due == NO_DUE ? 0 : (int) Math.max(0L, arrival - due),
                    exposureLimit == NO_EXPOSURE_LIMIT ? 0 : (int) Math.max(0L, arrival - exposureLimit)));
        }
        return new Evaluation(state.objective(), (int) state.distanceMeters(),
                (int) state.elapsedSeconds(), legs);
    }

    public static int clusterCountOf(RouteProblem problem) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RouteStop stop : problem.stops()) {
            keys.add(stop.clusterKey());
        }
        return keys.size();
    }

    private static long offsetOf(LocalDateTime departure, LocalDateTime target, long absent) {
        if (departure == null || target == null) {
            return absent;
        }
        return Duration.between(departure, target).getSeconds();
    }

    private static List<RouteCluster> buildClusters(RouteProblem problem) {
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < problem.stopCount(); i++) {
            grouped.computeIfAbsent(problem.stops().get(i).clusterKey(), key -> new ArrayList<>()).add(i);
        }
        Comparator<Integer> internalOrder = Comparator
                .<Integer, Long>comparing(index -> exposureRank(problem.stops().get(index)))
                .thenComparing(index -> dueRank(problem.stops().get(index)))
                .thenComparing(index -> problem.stops().get(index).taskId());
        List<RouteCluster> clusters = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<Integer>> entry : grouped.entrySet()) {
            List<Integer> members = new ArrayList<>(entry.getValue());
            members.sort(internalOrder);
            int[] indexes = new int[members.size()];
            for (int i = 0; i < members.size(); i++) {
                indexes[i] = members.get(i);
            }
            clusters.add(new RouteCluster(entry.getKey(), indexes));
        }
        return List.copyOf(clusters);
    }

    private static Long exposureRank(RouteStop stop) {
        Integer limit = stop.maxExposureSeconds();
        return limit == null ? Long.MAX_VALUE : limit.longValue();
    }

    private static LocalDateTime dueRank(RouteStop stop) {
        LocalDateTime due = stop.dueAt() != null ? stop.dueAt() : stop.windowEndAt();
        return due == null ? LocalDateTime.MAX : due;
    }

    public record PartialState(int node, long elapsedSeconds, long distanceMeters, double objective) {
    }

    public record RouteCluster(String key, int[] stopIndexes) {
        public int size() {
            return stopIndexes.length;
        }
    }

    public record Evaluation(
            double objective,
            int totalDistanceMeters,
            int totalDurationSeconds,
            List<RouteSolution.RouteLeg> legs
    ) {
    }
}
