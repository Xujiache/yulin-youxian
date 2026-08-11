package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.domain.RoutePlan;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRoutePlanDao implements RoutePlanDao {
    private final JdbcTemplate jdbcTemplate;

    public JdbcRoutePlanDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int nextPlanVersion(long waveId) {
        Integer current = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(plan_version), 0) FROM route_plan WHERE wave_id = ?", Integer.class, waveId);
        return (current == null ? 0 : current) + 1;
    }

    @Override
    public void deactivate(long waveId) {
        jdbcTemplate.update("UPDATE route_plan SET is_active = 0 WHERE wave_id = ? AND is_active = 1", waveId);
    }

    @Override
    public long insert(RoutePlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO route_plan (wave_id, rider_id, plan_version, trigger_reason, optimizer_name,"
                            + " matrix_provider, stop_count, total_distance_meters, total_duration_seconds,"
                            + " objective_value, solve_millis, sequence_json, polyline, is_active)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                    new String[]{"id"});
            statement.setObject(1, plan.waveId());
            statement.setObject(2, plan.riderId());
            statement.setInt(3, plan.planVersion());
            statement.setString(4, plan.triggerReason());
            statement.setString(5, plan.optimizerName());
            statement.setString(6, plan.matrixProvider());
            statement.setInt(7, plan.stopCount());
            statement.setInt(8, plan.totalDistanceMeters());
            statement.setInt(9, plan.totalDurationSeconds());
            statement.setBigDecimal(10, plan.objectiveValue());
            statement.setInt(11, plan.solveMillis());
            statement.setString(12, plan.sequenceJson());
            statement.setString(13, plan.polyline());
            return statement;
        }, keyHolder);
        Long key = JdbcValues.generatedIdOrNull(keyHolder);
        return key == null ? 0L : key;
    }

    @Override
    public Optional<RoutePlan> findActive(long waveId) {
        List<RoutePlan> rows = jdbcTemplate.query(
                "SELECT id, wave_id, rider_id, plan_version, trigger_reason, optimizer_name, matrix_provider,"
                        + " stop_count, total_distance_meters, total_duration_seconds, objective_value, solve_millis,"
                        + " sequence_json, polyline, is_active, created_at FROM route_plan"
                        + " WHERE wave_id = ? AND is_active = 1 ORDER BY plan_version DESC LIMIT 1",
                mapper(), waveId);
        return rows.stream().findFirst();
    }

    private RowMapper<RoutePlan> mapper() {
        return (rs, rowNum) -> new RoutePlan(
                rs.getLong("id"),
                nullableLong(rs, "wave_id"),
                nullableLong(rs, "rider_id"),
                rs.getInt("plan_version"),
                rs.getString("trigger_reason"),
                rs.getString("optimizer_name"),
                rs.getString("matrix_provider"),
                rs.getInt("stop_count"),
                rs.getInt("total_distance_meters"),
                rs.getInt("total_duration_seconds"),
                rs.getBigDecimal("objective_value"),
                rs.getInt("solve_millis"),
                rs.getString("sequence_json"),
                rs.getString("polyline"),
                rs.getBoolean("is_active"),
                dateTime(rs, "created_at"));
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
