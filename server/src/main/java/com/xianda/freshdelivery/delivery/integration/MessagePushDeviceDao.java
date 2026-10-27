package com.xianda.freshdelivery.delivery.integration;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessagePushDeviceDao {
    private final JdbcTemplate jdbcTemplate;

    public MessagePushDeviceDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findRegistrationIds(long riderId) {
        return jdbcTemplate.queryForList("""
                        SELECT push_registration_id FROM rider_device
                        WHERE rider_id = ? AND push_registration_id IS NOT NULL AND push_registration_id <> ''
                        ORDER BY last_seen_at DESC
                        """,
                String.class, riderId);
    }
}
