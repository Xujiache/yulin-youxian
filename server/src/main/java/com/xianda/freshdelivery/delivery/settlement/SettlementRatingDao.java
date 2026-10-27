package com.xianda.freshdelivery.delivery.settlement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementRatingDao {
    private final JdbcTemplate jdbcTemplate;

    public SettlementRatingDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int deleteByTask(long taskId) {
        return jdbcTemplate.update("DELETE FROM delivery_rating WHERE task_id = ?", taskId);
    }
}
