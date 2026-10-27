package com.xianda.freshdelivery.delivery.integration;

import com.xianda.freshdelivery.delivery.domain.PrivacyNumberBinding;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
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
public class PrivacyBindingDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, task_id, rider_id, provider, subscription_id, privacy_number, phone_a, phone_b,
            status, call_count, expire_at, released_at, created_at
            """;

    private static final RowMapper<PrivacyNumberBinding> ROW_MAPPER = (resultSet, rowNum) -> new PrivacyNumberBinding(
            resultSet.getLong("id"),
            resultSet.getLong("task_id"),
            resultSet.getLong("rider_id"),
            resultSet.getString("provider"),
            resultSet.getString("subscription_id"),
            resultSet.getString("privacy_number"),
            resultSet.getString("phone_a"),
            resultSet.getString("phone_b"),
            resultSet.getString("status"),
            resultSet.getInt("call_count"),
            JdbcValues.dateTime(resultSet, "expire_at"),
            JdbcValues.dateTime(resultSet, "released_at"),
            JdbcValues.dateTime(resultSet, "created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public PrivacyBindingDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(PrivacyNumberBinding binding, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO privacy_number_binding
                        (task_id, rider_id, provider, subscription_id, privacy_number, phone_a, phone_b,
                         status, call_count, expire_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setLong(1, binding.taskId());
            statement.setLong(2, binding.riderId());
            statement.setString(3, binding.provider());
            statement.setString(4, binding.subscriptionId());
            statement.setString(5, binding.privacyNumber());
            statement.setString(6, binding.phoneA());
            statement.setString(7, binding.phoneB());
            statement.setString(8, binding.status());
            statement.setInt(9, binding.callCount() == null ? 1 : binding.callCount());
            statement.setTimestamp(10, JdbcValues.timestamp(binding.expireAt()));
            statement.setTimestamp(11, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public Optional<PrivacyNumberBinding> findActive(long taskId, LocalDateTime now) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM privacy_number_binding"
                                + " WHERE task_id = ? AND status = 'ACTIVE' AND expire_at > ?"
                                + " ORDER BY id DESC",
                        ROW_MAPPER, taskId, JdbcValues.timestamp(now))
                .stream()
                .findFirst();
    }

    public Optional<PrivacyNumberBinding> findLatestUsable(long taskId, LocalDateTime now) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM privacy_number_binding"
                                + " WHERE task_id = ? AND status IN ('ACTIVE', 'DEGRADED') AND expire_at > ?"
                                + " ORDER BY id DESC",
                        ROW_MAPPER, taskId, JdbcValues.timestamp(now))
                .stream()
                .findFirst();
    }

    public int increaseCallCount(long id) {
        return jdbcTemplate.update("UPDATE privacy_number_binding SET call_count = call_count + 1 WHERE id = ?", id);
    }

    public List<PrivacyNumberBinding> findExpired(LocalDateTime now, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM privacy_number_binding"
                        + " WHERE status = 'ACTIVE' AND expire_at <= ? ORDER BY expire_at LIMIT ?",
                ROW_MAPPER, JdbcValues.timestamp(now), limit);
    }

    public int markReleased(long id, LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE privacy_number_binding SET status = 'RELEASED', released_at = ?
                        WHERE id = ? AND status = 'ACTIVE'
                        """,
                JdbcValues.timestamp(now), id);
    }

    public List<PrivacyNumberBinding> findByTask(long taskId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM privacy_number_binding WHERE task_id = ? ORDER BY id DESC",
                ROW_MAPPER, taskId);
    }
}
