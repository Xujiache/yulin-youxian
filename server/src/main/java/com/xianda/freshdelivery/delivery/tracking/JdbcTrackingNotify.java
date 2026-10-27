package com.xianda.freshdelivery.delivery.tracking;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcTrackingNotify implements TrackingNotifyPort {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTrackingNotify(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void notifyRider(
            Long riderId,
            String messageType,
            String title,
            String content,
            String priority,
            boolean needVoice,
            String linkType,
            String linkTarget
    ) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO rider_message
                                (rider_id, message_type, title, content, link_type, link_target,
                                 priority, need_voice, need_ack, push_status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING')
                            """,
                    riderId,
                    messageType,
                    title,
                    content,
                    linkType,
                    linkTarget,
                    priority == null ? "NORMAL" : priority,
                    needVoice ? 1 : 0
            );
        } catch (DataAccessException ignored) {
            return;
        }
    }
}
