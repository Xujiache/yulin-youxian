package com.xianda.freshdelivery.delivery.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TrackingBoardDao {
    public static final List<String> OPEN_STATUSES = List.of(
            "PENDING", "ASSIGNED", "ACCEPTED", "PICKED_UP", "DELIVERING", "ARRIVED", "EXCEPTION");

    private static final RowMapper<BoardTaskRow> BOARD_TASK_MAPPER = (resultSet, rowNum) -> new BoardTaskRow(
            resultSet.getLong("id"),
            resultSet.getString("task_no"),
            resultSet.getString("order_no"),
            resultSet.getLong("order_id"),
            resultSet.getString("status"),
            resultSet.getString("receiver_name"),
            resultSet.getString("receiver_phone"),
            resultSet.getString("receiver_phone_masked"),
            resultSet.getString("address_detail"),
            doubleOrNull(resultSet, "address_lat"),
            doubleOrNull(resultSet, "address_lng"),
            resultSet.getString("area_label"),
            resultSet.getString("building_label"),
            intOrNull(resultSet, "unit_no"),
            intOrNull(resultSet, "floor_no"),
            resultSet.getString("room_no"),
            resultSet.getInt("item_count"),
            resultSet.getDouble("total_weight_kg"),
            resultSet.getInt("package_count"),
            resultSet.getString("cold_chain_level"),
            resultSet.getString("goods_summary"),
            resultSet.getString("customer_remark"),
            resultSet.getString("delivery_instruction"),
            resultSet.getString("slot_label"),
            dateTime(resultSet, "promised_at"),
            dateTime(resultSet, "eta_at"),
            longOrNull(resultSet, "rider_id"),
            resultSet.getString("rider_name"),
            longOrNull(resultSet, "wave_id"),
            resultSet.getString("wave_no"),
            intOrNull(resultSet, "seq_no"),
            intOrNull(resultSet, "total_stops"),
            doubleOrNull(resultSet, "dispatch_score"),
            resultSet.getInt("reassign_count"),
            dateTime(resultSet, "hold_until_at"),
            dateTime(resultSet, "created_at"),
            dateTime(resultSet, "picked_ready_at"),
            longOrNull(resultSet, "current_exception_id")
    );

    private final JdbcTemplate jdbcTemplate;

    public TrackingBoardDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BoardTaskRow> openTasks() {
        return jdbcTemplate.query("""
                SELECT t.id, t.task_no, t.order_no, t.order_id, t.status, t.receiver_name, t.receiver_phone,
                       t.receiver_phone_masked, t.address_detail, t.address_lat, t.address_lng, t.area_label,
                       t.building_label, t.unit_no, t.floor_no, t.room_no, t.item_count, t.total_weight_kg,
                       t.package_count, t.cold_chain_level, t.goods_summary, t.customer_remark,
                       t.delivery_instruction, t.slot_label, t.promised_at, t.eta_at, t.rider_id,
                       r.name AS rider_name, t.wave_id, w.wave_no, s.seq_no,
                       (SELECT COUNT(*) FROM delivery_wave_stop ws WHERE ws.wave_id = t.wave_id) AS total_stops,
                       t.dispatch_score, t.reassign_count, t.hold_until_at, t.created_at, t.picked_ready_at,
                       t.current_exception_id
                FROM delivery_task t
                LEFT JOIN rider r ON r.id = t.rider_id
                LEFT JOIN delivery_wave w ON w.id = t.wave_id
                LEFT JOIN delivery_wave_stop s ON s.wave_id = t.wave_id AND s.task_id = t.id
                WHERE t.status IN ('PENDING', 'ASSIGNED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING',
                      'ARRIVED', 'EXCEPTION')
                ORDER BY t.id
                """, BOARD_TASK_MAPPER);
    }

    public List<MapTaskRow> mapTasks() {
        return jdbcTemplate.query("""
                SELECT id, address_lat, address_lng, status
                FROM delivery_task
                WHERE status IN ('PENDING', 'ASSIGNED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING',
                      'ARRIVED', 'EXCEPTION')
                  AND address_lat IS NOT NULL AND address_lng IS NOT NULL
                ORDER BY id
                """, (resultSet, rowNum) -> new MapTaskRow(
                resultSet.getLong("id"),
                resultSet.getDouble("address_lat"),
                resultSet.getDouble("address_lng"),
                resultSet.getString("status")));
    }

    public Map<Long, RiderLoadRow> riderLoads() {
        Map<Long, RiderLoadRow> loads = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT rider_id, COUNT(*) AS task_count, COALESCE(SUM(total_weight_kg), 0) AS weight_kg
                FROM delivery_task
                WHERE rider_id IS NOT NULL AND status IN ('ASSIGNED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING',
                      'ARRIVED', 'EXCEPTION')
                GROUP BY rider_id
                """, resultSet -> {
            loads.put(resultSet.getLong("rider_id"), new RiderLoadRow(
                    resultSet.getLong("rider_id"),
                    resultSet.getInt("task_count"),
                    resultSet.getDouble("weight_kg")));
        });
        return loads;
    }

    public List<WaveBriefRow> activeWaves() {
        return jdbcTemplate.query("""
                        SELECT w.id, w.wave_no, w.rider_id, r.name AS rider_name, w.status, w.task_count,
                               w.completed_count, w.plan_distance_meters, w.plan_return_at, w.max_cold_chain_level
                        FROM delivery_wave w
                        LEFT JOIN rider r ON r.id = w.rider_id
                        WHERE w.status IN ('PLANNING', 'ASSIGNED', 'PICKING', 'DELIVERING')
                        ORDER BY w.id
                        """,
                (resultSet, rowNum) -> new WaveBriefRow(
                        resultSet.getLong("id"),
                        resultSet.getString("wave_no"),
                        longOrNull(resultSet, "rider_id"),
                        resultSet.getString("rider_name"),
                        resultSet.getString("status"),
                        resultSet.getInt("task_count"),
                        resultSet.getInt("completed_count"),
                        resultSet.getInt("plan_distance_meters"),
                        dateTime(resultSet, "plan_return_at"),
                        resultSet.getString("max_cold_chain_level")));
    }

    public Map<Long, Long> currentWaveByRider() {
        Map<Long, Long> waves = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT rider_id, MAX(id) AS wave_id
                FROM delivery_wave
                WHERE rider_id IS NOT NULL AND status IN ('ASSIGNED', 'PICKING', 'DELIVERING')
                GROUP BY rider_id
                """, resultSet -> {
            waves.put(resultSet.getLong("rider_id"), resultSet.getLong("wave_id"));
        });
        return waves;
    }

    public Map<Long, LocalDateTime> planReturnByRider() {
        Map<Long, LocalDateTime> returns = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT rider_id, MAX(plan_return_at) AS plan_return_at
                FROM delivery_wave
                WHERE rider_id IS NOT NULL AND status IN ('ASSIGNED', 'PICKING', 'DELIVERING')
                GROUP BY rider_id
                """, resultSet -> {
            returns.put(resultSet.getLong("rider_id"),
                    resultSet.getTimestamp("plan_return_at") == null
                            ? null
                            : resultSet.getTimestamp("plan_return_at").toLocalDateTime());
        });
        return returns;
    }

    public int openExceptionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_exception WHERE status IN ('OPEN', 'PROCESSING')",
                Integer.class);
        return count == null ? 0 : count;
    }

    public DeliveredStats deliveredStats(LocalDate day) {
        return jdbcTemplate.query("""
                        SELECT COUNT(*) AS delivered_count,
                               COALESCE(AVG(CASE WHEN picked_up_at IS NOT NULL
                                    THEN TIMESTAMPDIFF(SECOND, picked_up_at, delivered_at) END), 0) AS avg_seconds,
                               COALESCE(SUM(CASE WHEN is_on_time = 1 THEN 1 ELSE 0 END), 0) AS on_time_count
                        FROM delivery_task
                        WHERE status = 'DELIVERED' AND delivered_at >= ? AND delivered_at < ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return new DeliveredStats(0, 0, 0);
                    }
                    return new DeliveredStats(
                            resultSet.getInt("delivered_count"),
                            (int) Math.round(resultSet.getDouble("avg_seconds")),
                            resultSet.getInt("on_time_count"));
                },
                TrackingTimes.timestamp(day.atStartOfDay()),
                TrackingTimes.timestamp(day.plusDays(1).atStartOfDay()));
    }

    public int stuckTaskCount(LocalDateTime threshold) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM delivery_task
                        WHERE status IN ('PENDING', 'ASSIGNED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING',
                              'ARRIVED', 'EXCEPTION')
                          AND updated_at < ?
                        """,
                Integer.class, TrackingTimes.timestamp(threshold));
        return count == null ? 0 : count;
    }

    private static LocalDateTime dateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null ? null : resultSet.getTimestamp(column).toLocalDateTime();
    }

    private static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer intOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    public record BoardTaskRow(
            Long taskId,
            String taskNo,
            String orderNo,
            Long orderId,
            String status,
            String receiverName,
            String receiverPhone,
            String receiverPhoneMasked,
            String addressDetail,
            Double addressLat,
            Double addressLng,
            String areaLabel,
            String buildingLabel,
            Integer unitNo,
            Integer floorNo,
            String roomNo,
            Integer itemCount,
            Double totalWeightKg,
            Integer packageCount,
            String coldChainLevel,
            String goodsSummary,
            String customerRemark,
            String deliveryInstruction,
            String slotLabel,
            LocalDateTime promisedAt,
            LocalDateTime etaAt,
            Long riderId,
            String riderName,
            Long waveId,
            String waveNo,
            Integer seqNo,
            Integer totalStops,
            Double dispatchScore,
            Integer reassignCount,
            LocalDateTime holdUntilAt,
            LocalDateTime createdAt,
            LocalDateTime pickedReadyAt,
            Long currentExceptionId
    ) {
    }

    public record MapTaskRow(Long taskId, Double lat, Double lng, String status) {
    }

    public record RiderLoadRow(Long riderId, Integer taskCount, Double weightKg) {
    }

    public record WaveBriefRow(
            Long waveId,
            String waveNo,
            Long riderId,
            String riderName,
            String status,
            Integer taskCount,
            Integer completedCount,
            Integer planDistanceMeters,
            LocalDateTime planReturnAt,
            String maxColdChainLevel
    ) {
    }

    public record DeliveredStats(Integer deliveredCount, Integer avgSeconds, Integer onTimeCount) {
    }
}
