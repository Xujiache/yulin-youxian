package com.xianda.freshdelivery.delivery.integration;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SubscribeTemplateDao {
    private static final RowMapper<SubscribeRow> ROW_MAPPER = (resultSet, rowNum) -> new SubscribeRow(
            resultSet.getLong("id"),
            resultSet.getLong("task_id"),
            resultSet.getString("detail_json")
    );

    private final JdbcTemplate jdbcTemplate;

    public SubscribeTemplateDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SubscribeRow> findSubscriptions(long taskId) {
        return jdbcTemplate.query("""
                        SELECT id, task_id, detail_json FROM delivery_task_event
                        WHERE task_id = ? AND event_type = 'SUBSCRIBE'
                        ORDER BY id DESC
                        """,
                ROW_MAPPER, taskId);
    }

    public record SubscribeRow(long eventId, long taskId, String detailJson) {
    }
}
