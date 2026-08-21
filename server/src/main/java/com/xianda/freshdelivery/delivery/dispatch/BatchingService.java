package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class BatchingService {
    private final RouteEstimator routeEstimator;
    private final DispatchSettings settings;

    public BatchingService(RouteEstimator routeEstimator, DispatchSettings settings) {
        this.routeEstimator = routeEstimator;
        this.settings = settings;
    }

    public List<TaskCluster> cluster(List<DispatchTaskRow> tasks, LocalDateTime now) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<TaskCluster> clusters = tasks.stream()
                .sorted(Comparator.comparingLong(DispatchTaskRow::taskId))
                .map(TaskCluster::single)
                .collect(Collectors.toCollection(ArrayList::new));

        clusters = mergeByKey(clusters, DispatchTaskRow::exactAddressKey, true, now);
        clusters = mergeByKey(clusters, DispatchTaskRow::buildingKey, true, now);
        clusters = mergeByKey(clusters, DispatchTaskRow::areaKey, false, now);
        clusters = mergeByProximity(clusters, now);

        List<TaskCluster> result = new ArrayList<>(clusters.size());
        for (TaskCluster cluster : clusters) {
            RouteEvaluation evaluation = evaluate(cluster, now);
            result.add(new TaskCluster(cluster.tasks(), batchReasonOf(cluster),
                    evaluation.totalDistanceMeters(), evaluation.totalDurationSeconds()));
        }
        result.sort(Comparator.comparingLong(cluster -> cluster.tasks().get(0).taskId()));
        return result;
    }

    public boolean violatesWaveLimits(TaskCluster cluster, LocalDateTime now) {
        if (cluster.singleTask()) {
            return false;
        }
        RouteEvaluation evaluation = evaluate(cluster, now);
        return evaluation.totalDistanceMeters() > settings.maxWaveDistanceMeters()
                || evaluation.totalDurationSeconds() > settings.maxWaveDurationSeconds();
    }

    public boolean timeWindowCompatible(TaskCluster cluster, LocalDateTime now) {
        if (cluster.singleTask()) {
            return true;
        }
        RouteEvaluation evaluation = evaluate(cluster, now);
        return !evaluation.causesOvertime() && !evaluation.coldChainAtRisk();
    }

    public boolean canAppendToWave(List<DispatchTaskRow> existingTasks,
                                   TaskCluster incoming,
                                   LocalDate waveDeliveryDate,
                                   LocalDateTime now) {
        if (incoming == null || incoming.tasks().isEmpty()) {
            return false;
        }
        List<DispatchTaskRow> combinedTasks = new ArrayList<>(
                existingTasks == null ? List.of() : existingTasks);
        combinedTasks.addAll(incoming.tasks());
        if (combinedTasks.size() > settings.maxTasksPerWave()
                || new HashSet<>(combinedTasks.stream().map(DispatchTaskRow::taskId).toList()).size()
                != combinedTasks.size()) {
            return false;
        }
        if (!sameDeliveryDate(combinedTasks, waveDeliveryDate)
                || !overlappingTimeWindows(combinedTasks)
                || mixedNearAndFar(combinedTasks)
                || combinedTasks.stream().mapToDouble(DispatchTaskRow::totalWeightKg).sum()
                > settings.maxWaveWeightKg()) {
            return false;
        }

        TaskCluster combined = new TaskCluster(
                combinedTasks, incoming.batchReason(), 0, 0);
        RouteEvaluation evaluation = evaluate(combined, now);
        if (!evaluation.unlocatedTaskIds().isEmpty()
                || evaluation.causesOvertime()
                || evaluation.coldChainAtRisk()) {
            return false;
        }
        if (combined.singleTask()) {
            return true;
        }
        return evaluation.totalDistanceMeters() <= settings.maxWaveDistanceMeters()
                && evaluation.totalDurationSeconds() <= settings.maxWaveDurationSeconds();
    }

    private List<TaskCluster> mergeByKey(List<TaskCluster> clusters,
                                         java.util.function.Function<DispatchTaskRow, String> keyOf,
                                         boolean forced,
                                         LocalDateTime now) {
        Map<String, List<TaskCluster>> grouped = new LinkedHashMap<>();
        List<TaskCluster> ungrouped = new ArrayList<>();
        for (TaskCluster cluster : clusters) {
            String key = sharedKey(cluster, keyOf);
            if (key == null) {
                ungrouped.add(cluster);
            } else {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cluster);
            }
        }
        List<TaskCluster> result = new ArrayList<>(ungrouped);
        for (List<TaskCluster> group : grouped.values()) {
            result.addAll(mergeGreedily(group, forced, now));
        }
        result.sort(Comparator.comparingLong(cluster -> cluster.tasks().get(0).taskId()));
        return result;
    }

    private List<TaskCluster> mergeByProximity(List<TaskCluster> clusters, LocalDateTime now) {
        int radius = settings.batchingRadiusMeters();
        List<TaskCluster> open = new ArrayList<>(clusters);
        List<TaskCluster> result = new ArrayList<>();
        while (!open.isEmpty()) {
            TaskCluster current = open.remove(0);
            boolean merged = true;
            while (merged) {
                merged = false;
                for (int index = 0; index < open.size(); index++) {
                    TaskCluster other = open.get(index);
                    if (centroidDistance(current, other) > radius) {
                        continue;
                    }
                    TaskCluster candidate = current.merged(other, DispatchCodes.BATCH_NEARBY);
                    if (!acceptable(candidate, false, now)) {
                        continue;
                    }
                    current = candidate;
                    open.remove(index);
                    merged = true;
                    break;
                }
            }
            result.add(current);
        }
        return result;
    }

    private List<TaskCluster> mergeGreedily(List<TaskCluster> group, boolean forced, LocalDateTime now) {
        List<TaskCluster> open = new ArrayList<>(group);
        List<TaskCluster> result = new ArrayList<>();
        while (!open.isEmpty()) {
            TaskCluster current = open.remove(0);
            boolean merged = true;
            while (merged) {
                merged = false;
                for (int index = 0; index < open.size(); index++) {
                    TaskCluster candidate = current.merged(open.get(index), DispatchCodes.BATCH_NEARBY);
                    if (!acceptable(candidate, forced, now)) {
                        continue;
                    }
                    current = candidate;
                    open.remove(index);
                    merged = true;
                    break;
                }
            }
            result.add(current);
        }
        return result;
    }

    private boolean acceptable(TaskCluster candidate, boolean forced, LocalDateTime now) {
        if (candidate.size() > settings.maxTasksPerWave()) {
            return false;
        }
        if (candidate.totalWeightKg() > settings.maxWaveWeightKg()) {
            return false;
        }
        if (forced) {
            return true;
        }
        return !violatesWaveLimits(candidate, now) && timeWindowCompatible(candidate, now);
    }

    private RouteEvaluation evaluate(TaskCluster cluster, LocalDateTime now) {
        GeoPoint origin = routeEstimator.storeOrigin();
        LocalDateTime departAt = now.plusSeconds(settings.pickupSeconds());
        return routeEstimator.evaluate(origin, now, departAt, cluster.tasks());
    }

    private static boolean sameDeliveryDate(List<DispatchTaskRow> tasks, LocalDate waveDeliveryDate) {
        if (waveDeliveryDate == null) {
            return false;
        }
        return tasks.stream().allMatch(task -> waveDeliveryDate.equals(task.deliveryDate()));
    }

    private static boolean overlappingTimeWindows(List<DispatchTaskRow> tasks) {
        LocalDateTime latestStart = null;
        LocalDateTime earliestEnd = null;
        for (DispatchTaskRow task : tasks) {
            LocalDateTime start = task.windowStartAt();
            LocalDateTime end = task.windowEndAt();
            if (start != null && end != null && start.isAfter(end)) {
                return false;
            }
            if (start != null && (latestStart == null || start.isAfter(latestStart))) {
                latestStart = start;
            }
            if (end != null && (earliestEnd == null || end.isBefore(earliestEnd))) {
                earliestEnd = end;
            }
        }
        return latestStart == null || earliestEnd == null || !latestStart.isAfter(earliestEnd);
    }

    private boolean mixedNearAndFar(List<DispatchTaskRow> tasks) {
        GeoPoint origin = routeEstimator.storeOrigin();
        if (origin == null) {
            return false;
        }
        Boolean far = null;
        for (DispatchTaskRow task : tasks) {
            if (!task.located()) {
                continue;
            }
            boolean current = origin.haversineMetersTo(task.location()) > settings.serviceRadiusMeters();
            if (far != null && far != current) {
                return true;
            }
            far = current;
        }
        return false;
    }

    private double centroidDistance(TaskCluster left, TaskCluster right) {
        GeoPoint leftCentroid = left.centroid();
        GeoPoint rightCentroid = right.centroid();
        if (leftCentroid == null || rightCentroid == null) {
            return Double.MAX_VALUE;
        }
        return leftCentroid.haversineMetersTo(rightCentroid);
    }

    private static String sharedKey(TaskCluster cluster,
                                    java.util.function.Function<DispatchTaskRow, String> keyOf) {
        Set<String> keys = cluster.tasks().stream()
                .map(keyOf)
                .filter(Objects::nonNull)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toSet());
        return keys.size() == 1 ? keys.iterator().next() : null;
    }

    private static String batchReasonOf(TaskCluster cluster) {
        if (cluster.singleTask()) {
            return DispatchCodes.BATCH_SINGLE;
        }
        if (sharedKey(cluster, DispatchTaskRow::exactAddressKey) != null) {
            return DispatchCodes.BATCH_SAME_ADDRESS;
        }
        if (sharedKey(cluster, DispatchTaskRow::buildingKey) != null) {
            return DispatchCodes.BATCH_SAME_BUILDING;
        }
        if (sharedKey(cluster, DispatchTaskRow::areaKey) != null) {
            return DispatchCodes.BATCH_SAME_AREA;
        }
        return DispatchCodes.BATCH_NEARBY;
    }
}
