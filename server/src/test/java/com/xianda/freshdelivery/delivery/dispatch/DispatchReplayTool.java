package com.xianda.freshdelivery.delivery.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 离线回放调参工具（05 文档 §12）。用历史 delivery_task / delivery_task_event 重放调度循环，
 * 网格搜索权重组合，输出准时率、总里程、骑手负载方差的对比表，把调参从拍脑袋变成可度量的实验。
 *
 * <p>典型用法：
 * <pre>
 *   DispatchReplayTool tool = new DispatchReplayTool();
 *   Scenario scenario = DispatchReplayTool.loadFromDatabase(jdbcTemplate,
 *           LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 14));
 *   System.out.println(tool.renderTable(tool.gridSearch(scenario, DispatchReplayTool.defaultGrid())));
 * </pre>
 */
public final class DispatchReplayTool {
    public static final int DEFAULT_MAX_ROUNDS = 200;
    public static final int DEFAULT_ROUND_STEP_SECONDS = 30;

    private final int maxRounds;
    private final int roundStepSeconds;

    public DispatchReplayTool() {
        this(DEFAULT_MAX_ROUNDS, DEFAULT_ROUND_STEP_SECONDS);
    }

    public DispatchReplayTool(int maxRounds, int roundStepSeconds) {
        this.maxRounds = maxRounds;
        this.roundStepSeconds = roundStepSeconds;
    }

    public List<Metrics> gridSearch(Scenario scenario, List<WeightCombination> grid) {
        List<Metrics> results = new ArrayList<>(grid.size());
        for (WeightCombination combination : grid) {
            results.add(replay(scenario, combination));
        }
        results.sort(Comparator.comparingDouble(Metrics::onTimeRate).reversed()
                .thenComparingLong(Metrics::totalDistanceMeters));
        return results;
    }

    public Metrics replay(Scenario scenario, WeightCombination weights) {
        DispatchFakes.MapConfigSource config = DispatchTestSupport.defaultConfig()
                .put(DispatchConfigKeys.STORE_LAT, scenario.storeLat())
                .put(DispatchConfigKeys.STORE_LNG, scenario.storeLng())
                .put(DispatchConfigKeys.WEIGHT_ADDED_DISTANCE, weights.addedDistance())
                .put(DispatchConfigKeys.WEIGHT_OVERTIME_RISK, weights.overtimeRisk())
                .put(DispatchConfigKeys.WEIGHT_LOAD_BALANCE, weights.loadBalance())
                .put(DispatchConfigKeys.WEIGHT_COLD_CHAIN, weights.coldChain())
                .put(DispatchConfigKeys.WEIGHT_RIDER_LEVEL, weights.riderLevel());
        DispatchSettings settings = DispatchTestSupport.settings(config);

        DispatchFakes.InMemoryDispatchDao dao = new DispatchFakes.InMemoryDispatchDao();
        scenario.tasks().forEach(dao::putTask);
        scenario.riders().forEach(dao::putRider);
        DispatchFakes.RecordingAssignmentPort assignmentPort = new DispatchFakes.RecordingAssignmentPort(dao);
        DispatchFakes.RecordingRoutingPort routingPort = new DispatchFakes.RecordingRoutingPort();

        RouteEstimator estimator = new RouteEstimator(new DispatchFakes.FakeRoutePlanningPort(), settings);
        RiderScoringService scoringService = new RiderScoringService(estimator, settings);
        MovableClock clock = new MovableClock(scenario.startAt());
        DispatchEngine engine = new DispatchEngine(
                dao,
                new BatchingService(estimator, settings),
                new HoldingWindowService(settings),
                scoringService,
                new ReassignmentService(dao, scoringService, estimator, assignmentPort, routingPort, settings),
                new CapacityAlertService(dao, scoringService, settings),
                new DispatchExplainer(new ObjectMapper()),
                assignmentPort,
                routingPort,
                settings,
                clock);

        int rounds = 0;
        while (rounds < maxRounds && dao.countPendingTasks() > 0) {
            engine.runOnce();
            clock.advanceSeconds(roundStepSeconds);
            rounds++;
        }
        return measure(weights, scenario, dao, estimator, settings, rounds);
    }

    private Metrics measure(WeightCombination weights,
                            Scenario scenario,
                            DispatchFakes.InMemoryDispatchDao dao,
                            RouteEstimator estimator,
                            DispatchSettings settings,
                            int rounds) {
        LocalDateTime now = scenario.startAt();
        LocalDateTime departAt = now.plusSeconds(settings.pickupSeconds());
        Map<Long, Integer> tasksPerRider = new LinkedHashMap<>();
        long totalDistance = 0L;
        int onTime = 0;
        int measured = 0;
        int coldViolations = 0;

        for (RiderCandidateRow rider : dao.findRiderPool()) {
            List<DispatchTaskRow> assigned = dao.findActiveTasksByRider(rider.riderId());
            tasksPerRider.put(rider.riderId(), assigned.size());
            if (assigned.isEmpty()) {
                continue;
            }
            RouteEvaluation evaluation = estimator.evaluate(settings.storeOrigin(), now, departAt, assigned);
            totalDistance += evaluation.totalDistanceMeters();
            for (DispatchTaskRow task : assigned) {
                LocalDateTime arriveAt = evaluation.arrivals().get(task.taskId());
                if (arriveAt == null || task.dueAt() == null) {
                    continue;
                }
                measured++;
                if (!arriveAt.isAfter(task.dueAt())) {
                    onTime++;
                }
                Integer maxExposure = task.coldChain().maxExposureSeconds();
                if (task.coldChain() != ColdChainLevel.NORMAL && maxExposure != null
                        && Duration.between(now, arriveAt).getSeconds() > maxExposure) {
                    coldViolations++;
                }
            }
        }
        int unassigned = dao.countPendingTasks();
        return new Metrics(weights,
                measured == 0 ? 0d : (double) onTime / measured,
                totalDistance,
                variance(tasksPerRider.values()),
                coldViolations,
                unassigned,
                rounds);
    }

    public String renderTable(List<Metrics> results) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.ROOT,
                "%-8s %-8s %-8s %-8s %-8s | %-9s %-12s %-10s %-8s %-8s%n",
                "顺路", "超时", "负载", "冷链", "等级",
                "准时率", "总里程(m)", "负载方差", "冷链违规", "未派出"));
        builder.append("-".repeat(104)).append(System.lineSeparator());
        for (Metrics metrics : results) {
            WeightCombination weights = metrics.weights();
            builder.append(String.format(Locale.ROOT,
                    "%-8.2f %-8.2f %-8.2f %-8.2f %-8.2f | %-9.2f%% %-12d %-10.3f %-8d %-8d%n",
                    weights.addedDistance(), weights.overtimeRisk(), weights.loadBalance(),
                    weights.coldChain(), weights.riderLevel(),
                    metrics.onTimeRate() * 100d, metrics.totalDistanceMeters(), metrics.loadVariance(),
                    metrics.coldChainViolations(), metrics.unassignedCount()));
        }
        return builder.toString();
    }

    public static List<WeightCombination> defaultGrid() {
        List<WeightCombination> grid = new ArrayList<>();
        for (double distance : new double[]{0.20d, 0.35d, 0.50d}) {
            for (double overtime : new double[]{0.15d, 0.30d, 0.45d}) {
                grid.add(WeightCombination.normalized(distance, overtime, 0.15d, 0.15d, 0.05d));
            }
        }
        return grid;
    }

    public static Scenario loadFromDatabase(JdbcTemplate jdbcTemplate, LocalDate from, LocalDate to) {
        List<DispatchTaskRow> tasks = jdbcTemplate.query(
                "SELECT id, task_no, wave_id, rider_id, status, address_detail, address_lat, address_lng,"
                        + " area_label, building_label, group_key, floor_no, room_no, item_count, total_weight_kg,"
                        + " cold_chain_level, delivery_date, window_start_at, window_end_at, promised_at, eta_at,"
                        + " extra_time_seconds, picked_ready_at, hold_until_at, reassign_count, priority,"
                        + " handoff_seconds FROM delivery_task WHERE delivery_date BETWEEN ? AND ? ORDER BY id",
                (rs, rowNum) -> replayTask(rs), from, to);
        List<RiderCandidateRow> riders = jdbcTemplate.query(
                "SELECT id, rider_no, name, account_status, vehicle_type, max_concurrent_task,"
                        + " capacity_weight_kg, probation, service_score, level_code FROM rider"
                        + " WHERE deleted_at IS NULL ORDER BY id",
                (rs, rowNum) -> replayRider(rs));
        LocalDateTime startAt = tasks.stream()
                .map(DispatchTaskRow::pickedReadyAt)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(from.atStartOfDay());
        Double lat = jdbcTemplate.queryForObject(
                "SELECT CAST(config_value AS DECIMAL(12,7)) FROM delivery_config WHERE config_key = 'store.lat'",
                Double.class);
        Double lng = jdbcTemplate.queryForObject(
                "SELECT CAST(config_value AS DECIMAL(12,7)) FROM delivery_config WHERE config_key = 'store.lng'",
                Double.class);
        return new Scenario(startAt, lat == null ? 0d : lat, lng == null ? 0d : lng,
                replayable(tasks, startAt), riders);
    }

    private static List<DispatchTaskRow> replayable(List<DispatchTaskRow> tasks, LocalDateTime startAt) {
        List<DispatchTaskRow> rewound = new ArrayList<>(tasks.size());
        for (DispatchTaskRow task : tasks) {
            rewound.add(new DispatchTaskRow(task.taskId(), task.taskNo(), null, null, "PENDING",
                    task.addressDetail(), task.lat(), task.lng(), task.areaLabel(), task.buildingLabel(),
                    task.groupKey(), task.floorNo(), task.roomNo(), task.itemCount(), task.totalWeightKg(),
                    task.coldChainLevel(), task.deliveryDate(), task.windowStartAt(), task.windowEndAt(),
                    task.promisedAt(), null, 0,
                    task.pickedReadyAt() == null ? startAt : task.pickedReadyAt(), null, 0, task.priority(),
                    task.handoffSeconds()));
        }
        return rewound;
    }

    private static DispatchTaskRow replayTask(ResultSet rs) throws SQLException {
        return new DispatchTaskRow(
                rs.getLong("id"), rs.getString("task_no"), null, null, rs.getString("status"),
                rs.getString("address_detail"), nullableDouble(rs, "address_lat"), nullableDouble(rs, "address_lng"),
                rs.getString("area_label"), rs.getString("building_label"), rs.getString("group_key"),
                nullableInt(rs, "floor_no"), rs.getString("room_no"), rs.getInt("item_count"),
                rs.getDouble("total_weight_kg"), rs.getString("cold_chain_level"),
                rs.getDate("delivery_date") == null ? null : rs.getDate("delivery_date").toLocalDate(),
                dateTime(rs, "window_start_at"), dateTime(rs, "window_end_at"), dateTime(rs, "promised_at"),
                dateTime(rs, "eta_at"), rs.getInt("extra_time_seconds"), dateTime(rs, "picked_ready_at"),
                dateTime(rs, "hold_until_at"), rs.getInt("reassign_count"), rs.getInt("priority"),
                nullableInt(rs, "handoff_seconds"));
    }

    private static RiderCandidateRow replayRider(ResultSet rs) throws SQLException {
        return new RiderCandidateRow(rs.getLong("id"), rs.getString("rider_no"), rs.getString("name"),
                rs.getString("account_status"), "ON_DUTY", rs.getString("vehicle_type"),
                rs.getInt("max_concurrent_task"), rs.getDouble("capacity_weight_kg"), rs.getBoolean("probation"),
                rs.getInt("service_score"), rs.getString("level_code"), null, null, null, null, null, null);
    }

    private static double variance(java.util.Collection<Integer> values) {
        if (values.isEmpty()) {
            return 0d;
        }
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0d);
        double sum = 0d;
        for (int value : values) {
            sum += Math.pow(value - mean, 2);
        }
        return sum / values.size();
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public record Scenario(
            LocalDateTime startAt,
            double storeLat,
            double storeLng,
            List<DispatchTaskRow> tasks,
            List<RiderCandidateRow> riders
    ) {
        public Scenario {
            tasks = List.copyOf(tasks);
            riders = List.copyOf(riders);
        }
    }

    public record WeightCombination(
            double addedDistance,
            double overtimeRisk,
            double loadBalance,
            double coldChain,
            double riderLevel
    ) {
        public static WeightCombination normalized(double addedDistance, double overtimeRisk, double loadBalance,
                                                   double coldChain, double riderLevel) {
            double total = addedDistance + overtimeRisk + loadBalance + coldChain + riderLevel;
            if (total <= 0d) {
                return new WeightCombination(0.35d, 0.30d, 0.15d, 0.15d, 0.05d);
            }
            return new WeightCombination(addedDistance / total, overtimeRisk / total, loadBalance / total,
                    coldChain / total, riderLevel / total);
        }
    }

    public record Metrics(
            WeightCombination weights,
            double onTimeRate,
            long totalDistanceMeters,
            double loadVariance,
            int coldChainViolations,
            int unassignedCount,
            int rounds
    ) {
    }

    static final class MovableClock extends Clock {
        private final java.time.ZoneId zone;
        private java.time.Instant instant;

        MovableClock(LocalDateTime start) {
            this(start, com.xianda.freshdelivery.delivery.account.DeliveryTimes.STORE_ZONE);
        }

        private MovableClock(LocalDateTime start, java.time.ZoneId zone) {
            this.zone = zone;
            this.instant = start.atZone(zone).toInstant();
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId targetZone) {
            return new MovableClock(LocalDateTime.ofInstant(instant, targetZone), targetZone);
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
