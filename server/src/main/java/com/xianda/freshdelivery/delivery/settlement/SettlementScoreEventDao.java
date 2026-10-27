package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.domain.RiderScoreEvent;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementScoreEventDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, rider_id, task_id, event_code, score_delta, score_after, reason, restorable,
            restored_at, operator_type, operator_name, created_at
            """;

    private static final RowMapper<RiderScoreEvent> ROW_MAPPER = (resultSet, rowNum) -> new RiderScoreEvent(
            resultSet.getLong("id"),
            resultSet.getLong("rider_id"),
            JdbcValues.longOrNull(resultSet, "task_id"),
            resultSet.getString("event_code"),
            resultSet.getInt("score_delta"),
            resultSet.getInt("score_after"),
            resultSet.getString("reason"),
            resultSet.getBoolean("restorable"),
            JdbcValues.dateTime(resultSet, "restored_at"),
            resultSet.getString("operator_type"),
            resultSet.getString("operator_name"),
            JdbcValues.dateTime(resultSet, "created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public SettlementScoreEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(RiderScoreEvent event, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rider_score_event
                        (rider_id, task_id, event_code, score_delta, score_after, reason, restorable,
                         operator_type, operator_name, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setLong(1, event.riderId());
            if (event.taskId() == null) {
                statement.setNull(2, Types.BIGINT);
            } else {
                statement.setLong(2, event.taskId());
            }
            statement.setString(3, event.eventCode());
            statement.setInt(4, event.scoreDelta() == null ? 0 : event.scoreDelta());
            statement.setInt(5, event.scoreAfter() == null ? 0 : event.scoreAfter());
            statement.setString(6, event.reason());
            statement.setBoolean(7, Boolean.TRUE.equals(event.restorable()));
            statement.setString(8, event.operatorType() == null ? "SYSTEM" : event.operatorType());
            statement.setString(9, event.operatorName());
            statement.setTimestamp(10, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public Optional<RiderScoreEvent> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_score_event WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<RiderScoreEvent> findByIdForUpdate(long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rider_score_event WHERE id = ? FOR UPDATE",
                ROW_MAPPER, id).stream().findFirst();
    }

    public List<RiderScoreEvent> findByRider(long riderId, int limit, int offset) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_score_event WHERE rider_id = ?"
                + " ORDER BY id DESC LIMIT ? OFFSET ?", ROW_MAPPER, riderId, limit, offset);
    }

    public long countByRider(long riderId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_score_event WHERE rider_id = ?", Long.class, riderId);
        return total == null ? 0L : total;
    }

    public List<RiderScoreEvent> findRestorable(long riderId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_score_event"
                        + " WHERE rider_id = ? AND restorable = 1 AND restored_at IS NULL AND score_delta < 0"
                        + " ORDER BY id",
                ROW_MAPPER, riderId);
    }

    public boolean existsForTaskAndCode(long taskId, String eventCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_score_event WHERE task_id = ? AND event_code = ?",
                Integer.class, taskId, eventCode);
        return count != null && count > 0;
    }

    public int markRestored(long id, LocalDateTime restoredAt) {
        return jdbcTemplate.update(
                "UPDATE rider_score_event SET restored_at = ? WHERE id = ? AND restored_at IS NULL",
                JdbcValues.timestamp(restoredAt), id);
    }

    public int countByRiderAndCodeSince(long riderId, String eventCode, LocalDateTime since) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM rider_score_event
                        WHERE rider_id = ? AND event_code = ? AND created_at >= ?
                        """,
                Integer.class, riderId, eventCode, JdbcValues.timestamp(since));
        return count == null ? 0 : count;
    }
}
