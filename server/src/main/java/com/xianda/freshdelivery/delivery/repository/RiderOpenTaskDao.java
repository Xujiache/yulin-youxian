package com.xianda.freshdelivery.delivery.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RiderOpenTaskDao {
    private static final String TERMINAL_STATUSES = "'DELIVERED', 'RETURNED', 'CANCELLED'";

    private static final RowMapper<TaskRef> ROW_MAPPER = (resultSet, rowNum) -> new TaskRef(
            resultSet.getLong("id"),
            resultSet.getString("task_no"),
            resultSet.getLong("order_id"),
            resultSet.getString("order_no"),
            resultSet.getString("status"),
            JdbcValues.longOrNull(resultSet, "rider_id")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderOpenTaskDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TaskRef> findOpenTasksByRider(long riderId) {
        return jdbcTemplate.query("""
                        SELECT id, task_no, order_id, order_no, status, rider_id
                        FROM delivery_task
                        WHERE rider_id = ? AND status NOT IN (%s)
                        ORDER BY id
                        """.formatted(TERMINAL_STATUSES),
                ROW_MAPPER, riderId);
    }

    public List<TaskRef> findNonTerminalTasks(int limit) {
        return jdbcTemplate.query("""
                        SELECT id, task_no, order_id, order_no, status, rider_id
                        FROM delivery_task
                        WHERE status NOT IN (%s)
                        ORDER BY id
                        LIMIT ?
                        """.formatted(TERMINAL_STATUSES),
                ROW_MAPPER, limit);
    }

    public record TaskRef(
            Long id,
            String taskNo,
            Long orderId,
            String orderNo,
            String status,
            Long riderId
    ) {
    }
}
