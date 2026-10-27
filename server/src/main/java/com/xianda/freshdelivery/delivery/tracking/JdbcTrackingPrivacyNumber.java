package com.xianda.freshdelivery.delivery.tracking;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcTrackingPrivacyNumber implements TrackingPrivacyNumberPort {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTrackingPrivacyNumber(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PrivacyCallNumber callNumberForCustomer(long taskId, Long riderId, String riderPhone) {
        String bound = activeBinding(taskId);
        if (bound != null) {
            return new PrivacyCallNumber(bound, false);
        }
        return new PrivacyCallNumber(riderPhone, true);
    }

    private String activeBinding(long taskId) {
        try {
            return jdbcTemplate.query("""
                            SELECT privacy_number FROM privacy_number_binding
                            WHERE task_id = ? AND status = 'ACTIVE'
                            ORDER BY id DESC LIMIT 1
                            """,
                    resultSet -> {
                        if (!resultSet.next()) {
                            return null;
                        }
                        String value = resultSet.getString(1);
                        return value == null || value.isBlank() ? null : value;
                    },
                    taskId
            );
        } catch (DataAccessException exception) {
            return null;
        }
    }
}
