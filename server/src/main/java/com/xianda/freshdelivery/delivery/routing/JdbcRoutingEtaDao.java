package com.xianda.freshdelivery.delivery.routing;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRoutingEtaDao implements RoutingEtaDao {
    private final JdbcTemplate jdbcTemplate;

    public JdbcRoutingEtaDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void updateEta(long taskId, LocalDateTime etaAt, LocalDateTime etaLowerAt, LocalDateTime etaUpperAt,
                          LocalDateTime etaUpdatedAt) {
        jdbcTemplate.update(
                "UPDATE delivery_task SET eta_at = ?, eta_lower_at = ?, eta_upper_at = ?, eta_updated_at = ?"
                        + " WHERE id = ?",
                timestamp(etaAt), timestamp(etaLowerAt), timestamp(etaUpperAt), timestamp(etaUpdatedAt), taskId);
    }

    @Override
    public void addExtraTime(long taskId, int deltaSeconds, String reason) {
        jdbcTemplate.update(
                "UPDATE delivery_task SET extra_time_seconds = extra_time_seconds + ?, extra_time_reason = ?"
                        + " WHERE id = ?",
                deltaSeconds, reason, taskId);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
