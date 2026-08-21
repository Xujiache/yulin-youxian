package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class DispatchContext {
    private final LocalDateTime now;
    private final GeoPoint storeOrigin;
    private final List<RiderCandidateRow> riders;
    private final Map<Long, List<DispatchTaskRow>> activeTasksByRider = new HashMap<>();
    private final Map<Long, RouteEvaluation> baselineCache = new HashMap<>();
    private final int pendingCount;

    public DispatchContext(LocalDateTime now,
                           GeoPoint storeOrigin,
                           List<RiderCandidateRow> riders,
                           Map<Long, List<DispatchTaskRow>> activeTasksByRider,
                           int pendingCount) {
        this.now = now;
        this.storeOrigin = storeOrigin;
        this.riders = List.copyOf(riders);
        this.pendingCount = pendingCount;
        activeTasksByRider.forEach((riderId, tasks) -> this.activeTasksByRider.put(riderId, new ArrayList<>(tasks)));
    }

    public LocalDateTime now() {
        return now;
    }

    public GeoPoint storeOrigin() {
        return storeOrigin;
    }

    public List<RiderCandidateRow> riders() {
        return riders;
    }

    public int pendingCount() {
        return pendingCount;
    }

    public List<DispatchTaskRow> activeTasksOf(long riderId) {
        return List.copyOf(activeTasksByRider.getOrDefault(riderId, List.of()));
    }

    public GeoPoint originFor(RiderCandidateRow rider) {
        if (storeOrigin != null) {
            return storeOrigin;
        }
        return rider == null ? null : rider.location();
    }

    public RouteEvaluation baselineOf(RiderCandidateRow rider, Supplier<RouteEvaluation> loader) {
        return baselineCache.computeIfAbsent(rider.riderId(), key -> loader.get());
    }

    public void recordAssignment(long riderId, List<DispatchTaskRow> tasks) {
        activeTasksByRider.computeIfAbsent(riderId, key -> new ArrayList<>()).addAll(tasks);
        baselineCache.remove(riderId);
    }

    public void recordReassignment(long fromRiderId, long toRiderId, DispatchTaskRow task) {
        activeTasksByRider.computeIfAbsent(fromRiderId, key -> new ArrayList<>())
                .removeIf(existing -> existing.taskId() == task.taskId());
        activeTasksByRider.computeIfAbsent(toRiderId, key -> new ArrayList<>()).add(task);
        baselineCache.remove(fromRiderId);
        baselineCache.remove(toRiderId);
    }

    public int onDutyRiderCount() {
        return (int) riders.stream().filter(rider -> rider.onDuty() && rider.active()).count();
    }

    public RiderCandidateRow riderOf(long riderId) {
        return riders.stream().filter(rider -> rider.riderId() == riderId).findFirst().orElse(null);
    }
}
