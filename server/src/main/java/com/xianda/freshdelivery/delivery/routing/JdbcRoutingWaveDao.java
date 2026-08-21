package com.xianda.freshdelivery.delivery.routing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRoutingWaveDao implements RoutingWaveDao {
    private static final String TASK_COLUMNS = "id, wave_id, status, address_lat, address_lng, cold_chain_level,"
            + " window_start_at, window_end_at, promised_at, group_key, area_label, building_label, floor_no,"
            + " extra_time_seconds, extra_time_reason, handoff_seconds, picked_ready_at, arrived_at, delivered_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcRoutingWaveDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<RoutingWaveRow> findWave(long waveId) {
        List<RoutingWaveRow> rows = jdbcTemplate.query(
                "SELECT id, wave_no, rider_id, status, assigned_at, started_at, route_plan_id"
                        + " FROM delivery_wave WHERE id = ?",
                waveMapper(), waveId);
        return rows.stream().findFirst();
    }

    @Override
    public List<RoutingTaskRow> findTasksByWave(long waveId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE wave_id = ? ORDER BY id",
                taskMapper(), waveId);
    }

    @Override
    public Optional<RoutingTaskRow> findTask(long taskId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE id = ?", taskMapper(), taskId)
                .stream().findFirst();
    }

    @Override
    public List<RoutingStopRow> findStops(long waveId) {
        return jdbcTemplate.query(
                "SELECT wave_id, task_id, seq_no, original_seq_no, lat, lng, leg_distance_meters, leg_duration_seconds,"
                        + " handoff_estimate_seconds, plan_arrive_at, plan_depart_at, actual_arrive_at, actual_depart_at,"
                        + " adjusted_by_rider FROM delivery_wave_stop WHERE wave_id = ? ORDER BY seq_no",
                stopMapper(), waveId);
    }

    @Override
    public void upsertStop(RoutingStopRow stop) {
        jdbcTemplate.update(
                "INSERT INTO delivery_wave_stop (wave_id, task_id, seq_no, original_seq_no, lat, lng,"
                        + " leg_distance_meters, leg_duration_seconds, handoff_estimate_seconds,"
                        + " plan_arrive_at, plan_depart_at, adjusted_by_rider)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE seq_no = VALUES(seq_no), lat = VALUES(lat), lng = VALUES(lng),"
                        + " leg_distance_meters = VALUES(leg_distance_meters),"
                        + " leg_duration_seconds = VALUES(leg_duration_seconds),"
                        + " handoff_estimate_seconds = VALUES(handoff_estimate_seconds),"
                        + " plan_arrive_at = VALUES(plan_arrive_at), plan_depart_at = VALUES(plan_depart_at),"
                        + " adjusted_by_rider = VALUES(adjusted_by_rider)",
                stop.waveId(), stop.taskId(), stop.seqNo(), stop.originalSeqNo(), stop.lat(), stop.lng(),
                stop.legDistanceMeters(), stop.legDurationSeconds(), stop.handoffEstimateSeconds(),
                timestamp(stop.planArriveAt()), timestamp(stop.planDepartAt()), stop.adjustedByRider() ? 1 : 0);
    }

    @Override
    public void updateWaveSummary(long waveId, int planDistanceMeters, int planDurationSeconds,
                                  LocalDateTime planReturnAt, long routePlanId,
                                  String optimizerName, String matrixProvider) {
        jdbcTemplate.update(
                "UPDATE delivery_wave SET plan_distance_meters = ?, plan_duration_seconds = ?, plan_return_at = ?,"
                        + " route_plan_id = ?, optimizer_name = ?, matrix_provider = ? WHERE id = ?",
                planDistanceMeters, planDurationSeconds, timestamp(planReturnAt), routePlanId,
                optimizerName, matrixProvider, waveId);
    }

    private RowMapper<RoutingWaveRow> waveMapper() {
        return (rs, rowNum) -> new RoutingWaveRow(
                rs.getLong("id"),
                rs.getString("wave_no"),
                nullableLong(rs, "rider_id"),
                rs.getString("status"),
                dateTime(rs, "assigned_at"),
                dateTime(rs, "started_at"),
                nullableLong(rs, "route_plan_id"));
    }

    private RowMapper<RoutingTaskRow> taskMapper() {
        return (rs, rowNum) -> new RoutingTaskRow(
                rs.getLong("id"),
                nullableLong(rs, "wave_id"),
                rs.getString("status"),
                nullableDouble(rs, "address_lat"),
                nullableDouble(rs, "address_lng"),
                rs.getString("cold_chain_level"),
                dateTime(rs, "window_start_at"),
                dateTime(rs, "window_end_at"),
                dateTime(rs, "promised_at"),
                rs.getString("group_key"),
                rs.getString("area_label"),
                rs.getString("building_label"),
                nullableInt(rs, "floor_no"),
                nullableInt(rs, "extra_time_seconds"),
                rs.getString("extra_time_reason"),
                nullableInt(rs, "handoff_seconds"),
                dateTime(rs, "picked_ready_at"),
                dateTime(rs, "arrived_at"),
                dateTime(rs, "delivered_at"));
    }

    private RowMapper<RoutingStopRow> stopMapper() {
        return (rs, rowNum) -> new RoutingStopRow(
                rs.getLong("wave_id"),
                rs.getLong("task_id"),
                nullableInt(rs, "seq_no"),
                nullableInt(rs, "original_seq_no"),
                nullableDouble(rs, "lat"),
                nullableDouble(rs, "lng"),
                nullableInt(rs, "leg_distance_meters"),
                nullableInt(rs, "leg_duration_seconds"),
                nullableInt(rs, "handoff_estimate_seconds"),
                dateTime(rs, "plan_arrive_at"),
                dateTime(rs, "plan_depart_at"),
                dateTime(rs, "actual_arrive_at"),
                dateTime(rs, "actual_depart_at"),
                rs.getBoolean("adjusted_by_rider"));
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
