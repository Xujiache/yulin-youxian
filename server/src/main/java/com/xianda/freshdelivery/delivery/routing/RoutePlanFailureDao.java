package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 路径规划失败留痕。
 *
 * 规划失败不影响派单，但会让骑手拿到一个没有路线的波次。以前只打日志，
 * 调度台看不见；这里把失败记下来，成功后再删掉。
 */
@Repository
public class RoutePlanFailureDao {

    private static final String COLUMNS =
            "wave_id, trigger_reason, error_message, attempt_count, first_failed_at, last_failed_at";

    private static final RowMapper<RoutePlanFailure> MAPPER = (rs, rowNum) -> new RoutePlanFailure(
            rs.getLong("wave_id"),
            rs.getString("trigger_reason"),
            rs.getString("error_message"),
            rs.getInt("attempt_count"),
            rs.getTimestamp("first_failed_at").toLocalDateTime(),
            rs.getTimestamp("last_failed_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbcTemplate;

    public RoutePlanFailureDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 同一波次反复失败时累加次数，一直涨说明不是偶发抖动。 */
    public void record(long waveId, String trigger, String error, LocalDateTime at) {
        jdbcTemplate.update("""
                INSERT INTO delivery_route_plan_failure
                    (wave_id, trigger_reason, error_message, attempt_count, first_failed_at, last_failed_at)
                VALUES (?, ?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    trigger_reason = VALUES(trigger_reason),
                    error_message  = VALUES(error_message),
                    attempt_count  = attempt_count + 1,
                    last_failed_at = VALUES(last_failed_at)
                """, waveId, clip(trigger, 48), clip(error, 512), at, at);
    }

    /** 规划成功就把这条抹掉，留着只会让调度台看到已经好了的问题。 */
    public void clear(long waveId) {
        jdbcTemplate.update("DELETE FROM delivery_route_plan_failure WHERE wave_id = ?", waveId);
    }

    public Optional<RoutePlanFailure> find(long waveId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_route_plan_failure WHERE wave_id = ?",
                MAPPER, waveId).stream().findFirst();
    }

    public List<RoutePlanFailure> recent(int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_route_plan_failure ORDER BY last_failed_at DESC LIMIT ?",
                MAPPER, limit);
    }

    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) {
            return "未知原因";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record RoutePlanFailure(
            long waveId,
            String triggerReason,
            String errorMessage,
            int attemptCount,
            LocalDateTime firstFailedAt,
            LocalDateTime lastFailedAt
    ) {}
}
