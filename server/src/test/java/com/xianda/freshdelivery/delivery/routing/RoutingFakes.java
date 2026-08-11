package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import com.xianda.freshdelivery.delivery.domain.BuildingHandoffStat;
import com.xianda.freshdelivery.delivery.domain.RoutePlan;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RoutingFakes {
    private RoutingFakes() {
    }

    static final class SingletonObjectProvider<T> implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T value;

        SingletonObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            if (value == null) {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(Object.class);
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
        public java.util.stream.Stream<T> stream() {
            return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value);
        }

        @Override
        public java.util.stream.Stream<T> orderedStream() {
            return stream();
        }
    }

    static final class InMemoryMatrixCacheDao implements MatrixCacheDao {
        private final Map<String, MatrixCacheDao.CachedEdge> rows = new LinkedHashMap<>();
        private final Map<String, LocalDateTime> expiries = new LinkedHashMap<>();
        private final Map<String, Integer> hits = new LinkedHashMap<>();

        @Override
        public Map<String, CachedEdge> findFresh(Collection<String> cacheKeys, LocalDateTime now) {
            Map<String, CachedEdge> found = new LinkedHashMap<>();
            for (String key : cacheKeys) {
                LocalDateTime expireAt = expiries.get(key);
                if (expireAt != null && expireAt.isAfter(now) && rows.containsKey(key)) {
                    found.put(key, rows.get(key));
                }
            }
            return found;
        }

        @Override
        public void upsertAll(List<CachedEdge> edges, LocalDateTime expireAt) {
            for (CachedEdge edge : edges) {
                rows.put(edge.cacheKey(), edge);
                expiries.put(edge.cacheKey(), expireAt);
            }
        }

        @Override
        public void incrementHits(Collection<String> cacheKeys) {
            for (String key : cacheKeys) {
                hits.merge(key, 1, Integer::sum);
            }
        }

        @Override
        public int deleteExpired(LocalDateTime now) {
            List<String> expired = new ArrayList<>();
            expiries.forEach((key, expireAt) -> {
                if (!expireAt.isAfter(now)) {
                    expired.add(key);
                }
            });
            expired.forEach(key -> {
                rows.remove(key);
                expiries.remove(key);
            });
            return expired.size();
        }

        int size() {
            return rows.size();
        }

        int hitsOf(String cacheKey) {
            return hits.getOrDefault(cacheKey, 0);
        }

        int totalHits() {
            return hits.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    static final class CountingMatrixProvider implements DistanceMatrixProvider {
        static final String NAME = "TEST_REMOTE";
        private int calls;

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public MatrixResult compute(GeoPoint origin, List<GeoPoint> destinations, TravelMode mode) {
            calls++;
            int[][] distance = new int[1][destinations.size()];
            int[][] duration = new int[1][destinations.size()];
            for (int j = 0; j < destinations.size(); j++) {
                distance[0][j] = 1000 + j;
                duration[0][j] = 200 + j;
            }
            return new MatrixResult(NAME, mode, distance, duration);
        }

        @Override
        public MatrixResult computeFull(List<GeoPoint> points, TravelMode mode) {
            calls++;
            int n = points.size();
            int[][] distance = new int[n][n];
            int[][] duration = new int[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        continue;
                    }
                    distance[i][j] = 1000 + i * 10 + j;
                    duration[i][j] = 200 + i * 10 + j;
                }
            }
            return new MatrixResult(NAME, mode, distance, duration);
        }

        int calls() {
            return calls;
        }
    }

    static final class InMemoryWaveDao implements RoutingWaveDao {
        private final Map<Long, RoutingWaveRow> waves = new LinkedHashMap<>();
        private final Map<Long, RoutingTaskRow> tasks = new LinkedHashMap<>();
        private final Map<Long, List<RoutingStopRow>> stops = new LinkedHashMap<>();
        private final List<RoutingStopRow> upserted = new ArrayList<>();

        void putWave(RoutingWaveRow wave) {
            waves.put(wave.waveId(), wave);
        }

        void putTask(RoutingTaskRow task) {
            tasks.put(task.taskId(), task);
        }

        void putStop(RoutingStopRow stop) {
            stops.computeIfAbsent(stop.waveId(), key -> new ArrayList<>()).add(stop);
        }

        List<RoutingStopRow> upsertedStops() {
            return upserted;
        }

        @Override
        public Optional<RoutingWaveRow> findWave(long waveId) {
            return Optional.ofNullable(waves.get(waveId));
        }

        @Override
        public List<RoutingTaskRow> findTasksByWave(long waveId) {
            List<RoutingTaskRow> result = new ArrayList<>();
            for (RoutingTaskRow task : tasks.values()) {
                if (task.waveId() != null && task.waveId() == waveId) {
                    result.add(task);
                }
            }
            return result;
        }

        @Override
        public Optional<RoutingTaskRow> findTask(long taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public List<RoutingStopRow> findStops(long waveId) {
            return new ArrayList<>(stops.getOrDefault(waveId, List.of()));
        }

        @Override
        public void upsertStop(RoutingStopRow stop) {
            upserted.add(stop);
            List<RoutingStopRow> existing = stops.computeIfAbsent(stop.waveId(), key -> new ArrayList<>());
            existing.removeIf(row -> row.taskId() == stop.taskId());
            existing.add(stop);
        }

        @Override
        public void updateWaveSummary(long waveId, int planDistanceMeters, int planDurationSeconds,
                                      LocalDateTime planReturnAt, long routePlanId,
                                      String optimizerName, String matrixProvider) {
        }
    }

    static final class RecordingEtaDao implements RoutingEtaDao {
        private final Map<Long, LocalDateTime> etas = new LinkedHashMap<>();
        private final List<ExtraTimeCall> extraTimeCalls = new ArrayList<>();

        @Override
        public void updateEta(long taskId, LocalDateTime etaAt, LocalDateTime etaLowerAt, LocalDateTime etaUpperAt,
                              LocalDateTime etaUpdatedAt) {
            etas.put(taskId, etaAt);
        }

        @Override
        public void addExtraTime(long taskId, int deltaSeconds, String reason) {
            extraTimeCalls.add(new ExtraTimeCall(taskId, deltaSeconds, reason));
        }

        Map<Long, LocalDateTime> etas() {
            return etas;
        }

        List<ExtraTimeCall> extraTimeCalls() {
            return extraTimeCalls;
        }

        record ExtraTimeCall(long taskId, int deltaSeconds, String reason) {
        }
    }

    static final class InMemoryHandoffStatDao implements HandoffStatDao {
        private final Map<String, List<BuildingHandoffStat>> byGroup = new LinkedHashMap<>();
        private final List<HandoffSample> samples = new ArrayList<>();
        private final Map<String, Integer> accessDenied = new LinkedHashMap<>();
        private final List<BuildingHandoffStat> upserted = new ArrayList<>();
        private final Map<String, Integer> appliedDifficulty = new LinkedHashMap<>();

        void putStat(BuildingHandoffStat stat) {
            byGroup.computeIfAbsent(stat.groupKey(), key -> new ArrayList<>()).add(stat);
        }

        void addSample(HandoffSample sample) {
            samples.add(sample);
        }

        void putAccessDenied(String groupKey, int count) {
            accessDenied.put(groupKey, count);
        }

        List<BuildingHandoffStat> upserted() {
            return upserted;
        }

        Map<String, Integer> appliedDifficulty() {
            return appliedDifficulty;
        }

        @Override
        public Optional<BuildingHandoffStat> find(String groupKey, String floorBucket) {
            return findByGroup(groupKey).stream()
                    .filter(stat -> floorBucket.equals(stat.floorBucket()))
                    .findFirst();
        }

        @Override
        public List<BuildingHandoffStat> findByGroup(String groupKey) {
            return byGroup.getOrDefault(groupKey, List.of());
        }

        @Override
        public List<HandoffSample> findSamples(LocalDateTime since) {
            return new ArrayList<>(samples);
        }

        @Override
        public Map<String, Integer> countAccessDeniedByGroup(LocalDateTime since) {
            return new LinkedHashMap<>(accessDenied);
        }

        @Override
        public void upsertStat(BuildingHandoffStat stat) {
            upserted.add(stat);
        }

        @Override
        public void applyAccessDifficulty(String groupKey, int difficulty) {
            appliedDifficulty.put(groupKey, difficulty);
        }

        @Override
        public void bumpAccessDifficulty(String groupKey, int cap) {
            appliedDifficulty.merge(groupKey, 1, (current, delta) -> Math.min(cap, current + delta));
        }
    }

    static final class InMemoryRoutePlanDao implements RoutePlanDao {
        private final List<RoutePlan> plans = new ArrayList<>();
        private long sequence;

        @Override
        public int nextPlanVersion(long waveId) {
            int max = 0;
            for (RoutePlan plan : plans) {
                if (plan.waveId() != null && plan.waveId() == waveId) {
                    max = Math.max(max, plan.planVersion());
                }
            }
            return max + 1;
        }

        @Override
        public void deactivate(long waveId) {
            plans.replaceAll(plan -> plan.waveId() != null && plan.waveId() == waveId
                    ? new RoutePlan(plan.id(), plan.waveId(), plan.riderId(), plan.planVersion(),
                    plan.triggerReason(), plan.optimizerName(), plan.matrixProvider(), plan.stopCount(),
                    plan.totalDistanceMeters(), plan.totalDurationSeconds(), plan.objectiveValue(),
                    plan.solveMillis(), plan.sequenceJson(), plan.polyline(), false, plan.createdAt())
                    : plan);
        }

        @Override
        public long insert(RoutePlan plan) {
            long id = ++sequence;
            plans.add(new RoutePlan(id, plan.waveId(), plan.riderId(), plan.planVersion(), plan.triggerReason(),
                    plan.optimizerName(), plan.matrixProvider(), plan.stopCount(), plan.totalDistanceMeters(),
                    plan.totalDurationSeconds(), plan.objectiveValue(), plan.solveMillis(), plan.sequenceJson(),
                    plan.polyline(), true, LocalDateTime.now()));
            return id;
        }

        @Override
        public Optional<RoutePlan> findActive(long waveId) {
            RoutePlan active = null;
            for (RoutePlan plan : plans) {
                if (plan.waveId() != null && plan.waveId() == waveId && Boolean.TRUE.equals(plan.isActive())) {
                    active = plan;
                }
            }
            return Optional.ofNullable(active);
        }

        List<RoutePlan> plans() {
            return plans;
        }
    }
}
