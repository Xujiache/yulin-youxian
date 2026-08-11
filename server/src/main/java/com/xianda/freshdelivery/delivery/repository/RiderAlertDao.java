package com.xianda.freshdelivery.delivery.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiderAlertDao {

    private final JdbcTemplate jdbcTemplate;

    public RiderAlertDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertSystemAlert(Long riderId, String title, String content, String priority, String linkTarget) {
        return jdbcTemplate.update("""
                        INSERT INTO rider_message
                            (rider_id, message_type, title, content, link_type, link_target, priority,
                             need_voice, need_ack, push_status)
                        VALUES (?, 'SYSTEM', ?, ?, ?, ?, ?, 0, 0, 'SKIPPED')
                        """,
                riderId,
                title,
                content,
                linkTarget == null ? "NONE" : "TASK",
                linkTarget,
                priority);
    }

    public int countSystemAlertsWithTarget(String linkTarget) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM rider_message
                        WHERE message_type = 'SYSTEM' AND link_target = ?
                        """,
                Integer.class, linkTarget);
        return count == null ? 0 : count;
    }
}
