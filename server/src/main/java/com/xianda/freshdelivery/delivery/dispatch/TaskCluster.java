package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.util.ArrayList;
import java.util.List;

public record TaskCluster(
        List<DispatchTaskRow> tasks,
        String batchReason,
        int routeDistanceMeters,
        int routeDurationSeconds
) {
    public TaskCluster {
        tasks = List.copyOf(tasks);
    }

    public static TaskCluster single(DispatchTaskRow task) {
        return new TaskCluster(List.of(task), DispatchCodes.BATCH_SINGLE, 0, 0);
    }

    public TaskCluster withRoute(int distanceMeters, int durationSeconds) {
        return new TaskCluster(tasks, batchReason, distanceMeters, durationSeconds);
    }

    public TaskCluster merged(TaskCluster other, String reason) {
        List<DispatchTaskRow> combined = new ArrayList<>(tasks);
        combined.addAll(other.tasks());
        combined.sort((left, right) -> Long.compare(left.taskId(), right.taskId()));
        return new TaskCluster(combined, reason, 0, 0);
    }

    public int size() {
        return tasks.size();
    }

    public boolean singleTask() {
        return tasks.size() == 1;
    }

    public List<Long> taskIds() {
        return tasks.stream().map(DispatchTaskRow::taskId).toList();
    }

    public double totalWeightKg() {
        return tasks.stream().mapToDouble(DispatchTaskRow::totalWeightKg).sum();
    }

    public int totalItemCount() {
        return tasks.stream().mapToInt(DispatchTaskRow::itemCount).sum();
    }

    public ColdChainLevel maxColdChain() {
        ColdChainLevel max = ColdChainLevel.NORMAL;
        for (DispatchTaskRow task : tasks) {
            if (task.coldChain().ordinal() > max.ordinal()) {
                max = task.coldChain();
            }
        }
        return max;
    }

    public GeoPoint centroid() {
        double latSum = 0d;
        double lngSum = 0d;
        int located = 0;
        for (DispatchTaskRow task : tasks) {
            if (task.located()) {
                latSum += task.lat();
                lngSum += task.lng();
                located++;
            }
        }
        return located == 0 ? null : new GeoPoint(latSum / located, lngSum / located);
    }
}
