package com.xianda.freshdelivery.delivery.tracking;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcTrackingConfig implements TrackingConfigPort {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(MAX_ACCURACY_METERS, "100"),
            Map.entry(GPS_SANITY_RADIUS_METERS, "200000"),
            Map.entry(REPORT_INTERVAL_STILL_SECONDS, "60"),
            Map.entry(REPORT_INTERVAL_WALKING_SECONDS, "20"),
            Map.entry(REPORT_INTERVAL_RIDING_SECONDS, "10"),
            Map.entry(LIVENESS_TIMEOUT_SECONDS, "120"),
            Map.entry(RETENTION_DAYS, "90"),
            Map.entry(ARRIVE_RADIUS_METERS, "80"),
            Map.entry(ARRIVE_DWELL_SECONDS, "30"),
            Map.entry(STORE_LAT, "0"),
            Map.entry(STORE_LNG, "0"),
            Map.entry(ETA_DISPLAY_AS_RANGE, "true"),
            Map.entry(ETA_RANGE_SPAN_MINUTES, "10"),
            Map.entry(PRIVACY_NUMBER_ENABLED, "false"),
            Map.entry(MAX_CONCURRENT_TASK, "8"),
            Map.entry(REQUIRE_VERIFY_CODE, "false"),
            Map.entry(REQUIRE_PHOTO, "true"),
            Map.entry(FATIGUE_WARN_SECONDS, "14400"),
            Map.entry(FATIGUE_CONFIRM_SECONDS, "28800"),
            Map.entry(FATIGUE_FORCE_SECONDS, "43200"),
            Map.entry(MATRIX_PROVIDER, "HAVERSINE"),
            Map.entry(PUSH_PROVIDER, "NOOP")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcTrackingConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getString(String key) {
        String stored = stored(key);
        return stored == null ? DEFAULTS.get(key) : stored;
    }

    @Override
    public int getInt(String key) {
        String value = getString(key);
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    @Override
    public boolean getBool(String key) {
        String value = getString(key);
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "true".equalsIgnoreCase(normalized) || "1".equals(normalized);
    }

    @Override
    public double getDecimal(String key) {
        String value = getString(key);
        try {
            return value == null ? 0d : Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return 0d;
        }
    }

    private String stored(String key) {
        try {
            return jdbcTemplate.query(
                    "SELECT config_value FROM delivery_config WHERE config_key = ?",
                    resultSet -> {
                        if (!resultSet.next()) {
                            return null;
                        }
                        String value = resultSet.getString(1);
                        return value == null || value.isBlank() ? null : value;
                    },
                    key
            );
        } catch (DataAccessException exception) {
            return null;
        }
    }
}
