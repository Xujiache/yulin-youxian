package com.xianda.freshdelivery.delivery.integration;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IntegrationTaskContactDao {
    private static final RowMapper<TaskContact> ROW_MAPPER = (resultSet, rowNum) -> new TaskContact(
            resultSet.getLong("id"),
            resultSet.getString("task_no"),
            resultSet.getObject("rider_id") == null ? null : resultSet.getLong("rider_id"),
            resultSet.getString("status"),
            resultSet.getString("receiver_phone"),
            resultSet.getString("receiver_phone_masked"),
            resultSet.getString("rider_phone")
    );

    private final JdbcTemplate jdbcTemplate;

    public IntegrationTaskContactDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TaskContact> findByTaskId(long taskId) {
        return jdbcTemplate.query("""
                                SELECT t.id, t.task_no, t.rider_id, t.status, t.receiver_phone,
                                       t.receiver_phone_masked, r.phone AS rider_phone
                                FROM delivery_task t
                                LEFT JOIN rider r ON r.id = t.rider_id
                                WHERE t.id = ?
                                """,
                        ROW_MAPPER, taskId)
                .stream()
                .findFirst();
    }

    public record TaskContact(
            long taskId,
            String taskNo,
            Long riderId,
            String status,
            String receiverPhone,
            String receiverPhoneMasked,
            String riderPhone
    ) {
    }
}
