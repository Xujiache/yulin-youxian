package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.account.DeliveryTimes;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

final class DispatchTestSupport {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0, 0);
    static final double STORE_LAT = 30.0d;
    static final double STORE_LNG = 120.0d;
    static final double METERS_PER_LAT_DEGREE = 111194.9d;

    private DispatchTestSupport() {
    }

    static DispatchFakes.MapConfigSource defaultConfig() {
        return new DispatchFakes.MapConfigSource()
                .put(DispatchConfigKeys.ENABLED, true)
                // 这些用例验的是派单算法本身，所以显式开全自动。
                // 生产默认是 ADVISORY（只推荐不执行），那条语义由 DispatchModeTests 守。
                .put(DispatchConfigKeys.MODE, "AUTO")
                .put(DispatchConfigKeys.HOLD_WINDOW_SECONDS, 120)
                .put(DispatchConfigKeys.MAX_TASKS_PER_WAVE, 8)
                .put(DispatchConfigKeys.MAX_WAVE_DISTANCE_METERS, 8000)
                .put(DispatchConfigKeys.MAX_WAVE_DURATION_SECONDS, 3600)
                .put(DispatchConfigKeys.MAX_WAVE_WEIGHT_KG, 30)
                .put(DispatchConfigKeys.BATCHING_RADIUS_METERS, 500)
                .put(DispatchConfigKeys.WEIGHT_ADDED_DISTANCE, "0.35")
                .put(DispatchConfigKeys.WEIGHT_OVERTIME_RISK, "0.30")
                .put(DispatchConfigKeys.WEIGHT_LOAD_BALANCE, "0.15")
                .put(DispatchConfigKeys.WEIGHT_COLD_CHAIN, "0.15")
                .put(DispatchConfigKeys.WEIGHT_RIDER_LEVEL, "0.05")
                .put(DispatchConfigKeys.PROBATION_MAX_TASKS, 3)
                .put(DispatchConfigKeys.PROBATION_MAX_DISTANCE_METERS, 2000)
                .put(DispatchConfigKeys.AUTO_REASSIGN_ENABLED, true)
                .put(DispatchConfigKeys.REASSIGN_MAX_COUNT, 2)
                .put(DispatchConfigKeys.MIN_SCORE_THRESHOLD, 0)
                .put(DispatchConfigKeys.ALERT_COOLDOWN_SECONDS, 300)
                .put(DispatchConfigKeys.STORE_LAT, STORE_LAT)
                .put(DispatchConfigKeys.STORE_LNG, STORE_LNG)
                .put(DispatchConfigKeys.STORE_SERVICE_RADIUS_METERS, 3000)
                .put(DispatchConfigKeys.LIVENESS_TIMEOUT_SECONDS, 120)
                .put(DispatchConfigKeys.PICKUP_SECONDS, 300)
                .put(DispatchConfigKeys.DEFAULT_HANDOFF_SECONDS, 180)
                .put(DispatchConfigKeys.SCORE_MAX, 120);
    }

    static DispatchSettings settings(DispatchFakes.MapConfigSource configSource) {
        return new DispatchSettings(configSource,
                new DispatchFakes.SingletonObjectProvider<DeliveryProperties>(null));
    }

    static Clock fixedClock() {
        return Clock.fixed(NOW.atZone(DeliveryTimes.STORE_ZONE).toInstant(), DeliveryTimes.STORE_ZONE);
    }

    static double latAtMeters(double meters) {
        return STORE_LAT + meters / METERS_PER_LAT_DEGREE;
    }

    static TaskBuilder task(long taskId) {
        return new TaskBuilder(taskId);
    }

    static RiderBuilder rider(long riderId) {
        return new RiderBuilder(riderId);
    }

    static DispatchContext context(DispatchSettings settings,
                                   java.util.List<RiderCandidateRow> riders,
                                   java.util.Map<Long, java.util.List<DispatchTaskRow>> activeTasks,
                                   int pendingCount) {
        return new DispatchContext(NOW, settings.storeOrigin(), riders, activeTasks, pendingCount);
    }

    static DispatchTaskRow withAssignment(DispatchTaskRow task, long riderId, Long waveId, String status) {
        return new DispatchTaskRow(task.taskId(), task.taskNo(), waveId, riderId, status, task.addressDetail(),
                task.lat(), task.lng(), task.areaLabel(), task.buildingLabel(), task.groupKey(), task.floorNo(),
                task.roomNo(), task.itemCount(), task.totalWeightKg(), task.coldChainLevel(), task.deliveryDate(),
                task.windowStartAt(), task.windowEndAt(), task.promisedAt(), task.etaAt(), task.extraTimeSeconds(),
                task.pickedReadyAt(), task.holdUntilAt(), task.reassignCount(), task.priority(),
                task.handoffSeconds());
    }

    static final class TaskBuilder {
        private final long taskId;
        private String status = "PENDING";
        private Long waveId;
        private Long riderId;
        private String addressDetail = "示范小区 1号楼 2单元 301室";
        private double lat = STORE_LAT;
        private double lng = STORE_LNG;
        private String areaLabel = "示范小区";
        private String buildingLabel = "1号楼";
        private String groupKey = "示范小区|building:0:00000001";
        private Integer floorNo = 3;
        private String roomNo = "301";
        private int itemCount = 5;
        private double totalWeightKg = 3.0d;
        private String coldChainLevel = "NORMAL";
        private LocalDate deliveryDate = LocalDate.from(NOW);
        private LocalDateTime windowStartAt = NOW.minusSeconds(1800);
        private LocalDateTime windowEndAt = NOW.plusSeconds(3600);
        private LocalDateTime promisedAt = NOW.plusSeconds(3600);
        private LocalDateTime etaAt;
        private LocalDateTime pickedReadyAt = NOW.minusSeconds(600);
        private LocalDateTime holdUntilAt = NOW.minusSeconds(480);
        private int reassignCount;
        private int priority;
        private Integer handoffSeconds = 0;

        private TaskBuilder(long taskId) {
            this.taskId = taskId;
        }

        TaskBuilder status(String value) {
            this.status = value;
            return this;
        }

        TaskBuilder rider(Long value) {
            this.riderId = value;
            return this;
        }

        TaskBuilder wave(Long value) {
            this.waveId = value;
            return this;
        }

        TaskBuilder atMeters(double meters) {
            this.lat = latAtMeters(meters);
            this.lng = STORE_LNG;
            return this;
        }

        TaskBuilder address(String area, String building, String detail, String room) {
            this.areaLabel = area;
            this.buildingLabel = building;
            this.addressDetail = detail;
            this.roomNo = room;
            this.groupKey = area + "|building:" + building;
            return this;
        }

        TaskBuilder groupKey(String value) {
            this.groupKey = value;
            return this;
        }

        TaskBuilder coldChain(String value) {
            this.coldChainLevel = value;
            return this;
        }

        TaskBuilder weightKg(double value) {
            this.totalWeightKg = value;
            return this;
        }

        TaskBuilder dueAt(LocalDateTime value) {
            this.windowEndAt = value;
            this.promisedAt = value;
            return this;
        }

        TaskBuilder deliveryDate(LocalDate value) {
            this.deliveryDate = value;
            return this;
        }

        TaskBuilder window(LocalDateTime start, LocalDateTime end) {
            this.windowStartAt = start;
            this.windowEndAt = end;
            this.promisedAt = end;
            return this;
        }

        TaskBuilder etaAt(LocalDateTime value) {
            this.etaAt = value;
            return this;
        }

        TaskBuilder pickedReadyAt(LocalDateTime value) {
            this.pickedReadyAt = value;
            return this;
        }

        TaskBuilder holdUntilAt(LocalDateTime value) {
            this.holdUntilAt = value;
            return this;
        }

        TaskBuilder reassignCount(int value) {
            this.reassignCount = value;
            return this;
        }

        TaskBuilder handoffSeconds(int value) {
            this.handoffSeconds = value;
            return this;
        }

        DispatchTaskRow build() {
            return new DispatchTaskRow(taskId, "PS-TEST-" + taskId, waveId, riderId, status, addressDetail,
                    lat, lng, areaLabel, buildingLabel, groupKey, floorNo, roomNo, itemCount, totalWeightKg,
                    coldChainLevel, deliveryDate, windowStartAt, windowEndAt, promisedAt, etaAt,
                    0, pickedReadyAt, holdUntilAt, reassignCount, priority, handoffSeconds);
        }
    }

    static final class RiderBuilder {
        private final long riderId;
        private String accountStatus = "ACTIVE";
        private String workStatus = "ON_DUTY";
        private int maxConcurrentTask = 8;
        private double capacityWeightKg = 30.0d;
        private boolean probation;
        private int serviceScore = 90;
        private LocalDateTime dispatchPausedUntil;
        private Double lat = STORE_LAT;
        private Double lng = STORE_LNG;
        private LocalDateTime locatedAt = NOW.minusSeconds(10);

        private RiderBuilder(long riderId) {
            this.riderId = riderId;
        }

        RiderBuilder accountStatus(String value) {
            this.accountStatus = value;
            return this;
        }

        RiderBuilder workStatus(String value) {
            this.workStatus = value;
            return this;
        }

        RiderBuilder maxConcurrentTask(int value) {
            this.maxConcurrentTask = value;
            return this;
        }

        RiderBuilder capacityWeightKg(double value) {
            this.capacityWeightKg = value;
            return this;
        }

        RiderBuilder probation(boolean value) {
            this.probation = value;
            return this;
        }

        RiderBuilder serviceScore(int value) {
            this.serviceScore = value;
            return this;
        }

        RiderBuilder fatiguePausedUntil(LocalDateTime value) {
            this.dispatchPausedUntil = value;
            return this;
        }

        RiderBuilder locatedAt(LocalDateTime value) {
            this.locatedAt = value;
            return this;
        }

        RiderCandidateRow build() {
            return new RiderCandidateRow(riderId, "QS" + riderId, "骑手" + riderId, accountStatus, workStatus,
                    "EBIKE", maxConcurrentTask, capacityWeightKg, probation, serviceScore, "L1", 1L,
                    dispatchPausedUntil, lat, lng, locatedAt, null);
        }
    }
}
