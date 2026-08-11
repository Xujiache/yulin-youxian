package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

final class DispatchFakes {
    private DispatchFakes() {
    }

    static final class SingletonObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        SingletonObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            if (value == null) {
                throw new NoSuchBeanDefinitionException(Object.class);
            }
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return getObject();
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public Stream<T> stream() {
            return value == null ? Stream.empty() : Stream.of(value);
        }

        @Override
        public Stream<T> orderedStream() {
            return stream();
        }
    }

    static final class MapConfigSource implements DispatchConfigSource {
        private final Map<String, String> values = new LinkedHashMap<>();

        MapConfigSource put(String key, Object value) {
            values.put(key, String.valueOf(value));
            return this;
        }

        @Override
        public String getString(String key) {
            return values.get(key);
        }

        @Override
        public Integer getInt(String key) {
            BigDecimal decimal = getDecimal(key);
            return decimal == null ? null : decimal.intValue();
        }

        @Override
        public Boolean getBool(String key) {
            String value = values.get(key);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        @Override
        public BigDecimal getDecimal(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(value.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    static final class FakeRoutePlanningPort implements RoutePlanningPort {
        static final double SPEED_MPS = 15d / 3.6d;

        private Integer fixedLegDistanceMeters;
        private int calls;

        FakeRoutePlanningPort withFixedLegDistance(int meters) {
            this.fixedLegDistanceMeters = meters;
            return this;
        }

        int calls() {
            return calls;
        }

        @Override
        public RoutePlanEstimate estimateRoute(GeoPoint origin, List<RouteStopInput> stops) {
            calls++;
            if (stops == null || stops.isEmpty()) {
                return new RoutePlanEstimate(0, 0, 0d, List.of());
            }
            List<RouteStopInput> ordered = new ArrayList<>(stops);
            ordered.sort(Comparator.comparingLong(stop -> stop.taskId() == null ? 0L : stop.taskId()));
            List<RouteLegEstimate> legs = new ArrayList<>(ordered.size());
            GeoPoint previous = origin;
            int totalDistance = 0;
            int totalDuration = 0;
            int seq = 0;
            for (RouteStopInput stop : ordered) {
                seq++;
                int distance = fixedLegDistanceMeters != null
                        ? fixedLegDistanceMeters
                        : (int) Math.round(straightMeters(previous, stop.location()));
                int duration = (int) Math.round(distance / SPEED_MPS);
                totalDistance += distance;
                totalDuration += duration;
                legs.add(new RouteLegEstimate(stop.taskId(), seq, distance, duration));
                if (stop.location() != null) {
                    previous = stop.location();
                }
            }
            return new RoutePlanEstimate(totalDistance, totalDuration, 0d, legs);
        }

        private static double straightMeters(GeoPoint from, GeoPoint to) {
            if (from == null || to == null || from.lat() == null || to.lat() == null) {
                return 0d;
            }
            return from.haversineMetersTo(to);
        }
    }

    static final class InMemoryDispatchDao implements DispatchDao {
        private final Map<Long, DispatchTaskRow> tasks = new LinkedHashMap<>();
        private final Map<Long, RiderCandidateRow> riders = new LinkedHashMap<>();
        private final Map<Long, DispatchWaveRow> waves = new LinkedHashMap<>();
        private final List<Message> messages = new ArrayList<>();
        private long waveSequence;

        synchronized void putTask(DispatchTaskRow task) {
            tasks.put(task.taskId(), task);
        }

        synchronized void putRider(RiderCandidateRow rider) {
            riders.put(rider.riderId(), rider);
        }

        synchronized DispatchTaskRow task(long taskId) {
            return tasks.get(taskId);
        }

        List<Message> messages() {
            return messages;
        }

        synchronized List<DispatchWaveRow> waves() {
            return new ArrayList<>(waves.values());
        }

        synchronized void applyAssignment(long taskId, long riderId, Long waveId, String status) {
            DispatchTaskRow current = tasks.get(taskId);
            if (current == null) {
                throw new IllegalStateException("任务不存在：" + taskId);
            }
            tasks.put(taskId, DispatchTestSupport.withAssignment(current, riderId, waveId, status));
        }

        @Override
        public synchronized List<DispatchTaskRow> findPendingTasks() {
            return tasks.values().stream()
                    .filter(task -> "PENDING".equals(task.status()))
                    .sorted(Comparator.comparingLong(DispatchTaskRow::taskId))
                    .toList();
        }

        @Override
        public synchronized List<DispatchTaskRow> findTasksByIds(List<Long> taskIds) {
            if (taskIds == null) {
                return List.of();
            }
            return taskIds.stream().map(tasks::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public synchronized List<DispatchTaskRow> findTasksByWave(long waveId) {
            return tasks.values().stream()
                    .filter(task -> task.waveId() != null && task.waveId() == waveId)
                    .sorted(Comparator.comparingLong(DispatchTaskRow::taskId))
                    .toList();
        }

        @Override
        public synchronized Optional<DispatchTaskRow> findTask(long taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public synchronized List<DispatchTaskRow> lockPendingTasks(List<Long> taskIds) {
            if (taskIds == null) {
                return List.of();
            }
            return taskIds.stream()
                    .distinct()
                    .sorted()
                    .map(tasks::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(task -> "PENDING".equals(task.status()))
                    .filter(task -> task.riderId() == null && task.waveId() == null)
                    .toList();
        }

        @Override
        public synchronized Optional<DispatchTaskRow> lockTask(long taskId) {
            return findTask(taskId);
        }

        @Override
        public synchronized boolean lockRiders(List<Long> riderIds) {
            return riderIds != null
                    && riderIds.stream().distinct().allMatch(riders::containsKey);
        }

        @Override
        public synchronized List<DispatchTaskRow> findActiveTasksByRider(long riderId) {
            return tasks.values().stream()
                    .filter(task -> task.riderId() != null && task.riderId() == riderId)
                    .filter(task -> List.of("ASSIGNED", "ACCEPTED", "PICKED_UP", "DELIVERING", "ARRIVED")
                            .contains(task.status()))
                    .sorted(Comparator.comparingLong(DispatchTaskRow::taskId))
                    .toList();
        }

        @Override
        public synchronized List<DispatchTaskRow> findReassignCandidates() {
            return tasks.values().stream()
                    .filter(task -> task.riderId() != null)
                    .filter(task -> List.of("ASSIGNED", "ACCEPTED").contains(task.status()))
                    .sorted(Comparator.comparingLong(DispatchTaskRow::taskId))
                    .toList();
        }

        @Override
        public synchronized List<RiderCandidateRow> findRiderPool() {
            return new ArrayList<>(riders.values());
        }

        @Override
        public synchronized Optional<RiderCandidateRow> findRider(long riderId) {
            return Optional.ofNullable(riders.get(riderId));
        }

        @Override
        public synchronized Optional<DispatchWaveRow> findAppendableWave(long riderId) {
            return waves.values().stream()
                    .filter(wave -> wave.riderId() != null && wave.riderId() == riderId)
                    .filter(DispatchWaveRow::openForAppend)
                    .max(Comparator.comparingLong(DispatchWaveRow::waveId));
        }

        @Override
        public synchronized long createWave(Long riderId, LocalDate deliveryDate, LocalDateTime now) {
            long waveId = ++waveSequence;
            waves.put(waveId, new DispatchWaveRow(waveId, "BC-TEST-" + waveId, riderId,
                    riderId == null ? "PLANNING" : "ASSIGNED", deliveryDate, 0));
            return waveId;
        }

        @Override
        public synchronized boolean bindReassignedTaskToWave(
                long taskId, long riderId, long waveId, LocalDateTime now) {
            DispatchTaskRow task = tasks.get(taskId);
            if (task == null || task.riderId() == null || task.riderId() != riderId
                    || task.waveId() != null || !List.of("ASSIGNED", "ACCEPTED").contains(task.status())) {
                return false;
            }
            applyAssignment(taskId, riderId, waveId, task.status());
            return true;
        }

        @Override
        public synchronized void refreshWaveAggregates(long waveId, LocalDateTime now) {
            DispatchWaveRow wave = waves.get(waveId);
            if (wave == null) {
                return;
            }
            int count = (int) tasks.values().stream()
                    .filter(task -> task.waveId() != null && task.waveId() == waveId).count();
            waves.put(waveId, new DispatchWaveRow(wave.waveId(), wave.waveNo(), wave.riderId(), wave.status(),
                    wave.deliveryDate(), count));
        }

        @Override
        public synchronized boolean deleteWaveIfEmpty(long waveId) {
            boolean hasTask = tasks.values().stream()
                    .anyMatch(task -> task.waveId() != null && task.waveId() == waveId);
            return !hasTask && waves.remove(waveId) != null;
        }

        @Override
        public synchronized int countPendingTasks() {
            return findPendingTasks().size();
        }

        @Override
        public void insertMessage(Long riderId, String messageType, String title, String content,
                                  String priority, boolean needVoice, String linkType, String linkTarget) {
            messages.add(new Message(riderId, messageType, title, content, priority));
        }

        record Message(Long riderId, String messageType, String title, String content, String priority) {
        }
    }

    static final class RecordingAssignmentPort implements TaskAssignmentPort {
        private final InMemoryDispatchDao dao;
        private final List<AssignCall> assigns = new ArrayList<>();
        private final List<ReassignCall> reassigns = new ArrayList<>();
        private RuntimeException failure;

        RecordingAssignmentPort(InMemoryDispatchDao dao) {
            this.dao = dao;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        List<AssignCall> assigns() {
            return assigns;
        }

        List<ReassignCall> reassigns() {
            return reassigns;
        }

        @Override
        public void assignTask(long taskId, long riderId, Long waveId, String dispatchMode, Double dispatchScore,
                               String detailJson) {
            if (failure != null) {
                throw failure;
            }
            assigns.add(new AssignCall(taskId, riderId, waveId, dispatchMode, dispatchScore, detailJson));
            dao.applyAssignment(taskId, riderId, waveId, "ASSIGNED");
        }

        @Override
        public void reassignTask(long taskId, long toRiderId, String reason, String operatorType,
                                 String operatorName) {
            if (failure != null) {
                throw failure;
            }
            reassigns.add(new ReassignCall(taskId, toRiderId, reason, operatorType, operatorName));
            dao.applyAssignment(taskId, toRiderId, null, "ASSIGNED");
        }

        record AssignCall(long taskId, long riderId, Long waveId, String dispatchMode, Double dispatchScore,
                          String detailJson) {
        }

        record ReassignCall(long taskId, long toRiderId, String reason, String operatorType, String operatorName) {
        }
    }

    static final class RecordingRoutingPort implements WaveRoutingPort {
        private final List<Long> plannedWaves = new ArrayList<>();
        private final List<Long> replannedWaves = new ArrayList<>();
        private final List<Long> etaWaves = new ArrayList<>();

        List<Long> plannedWaves() {
            return plannedWaves;
        }

        List<Long> replannedWaves() {
            return replannedWaves;
        }

        List<Long> etaWaves() {
            return etaWaves;
        }

        @Override
        public void planWave(long waveId, boolean newTaskJoinedExistingWave) {
            plannedWaves.add(waveId);
        }

        @Override
        public void replanAfterReassign(long waveId) {
            replannedWaves.add(waveId);
        }

        @Override
        public void recomputeWaveEta(long waveId) {
            etaWaves.add(waveId);
        }
    }
}
