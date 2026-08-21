package com.xianda.freshdelivery.delivery.task;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JdbcDeliveryConfigPort implements DeliveryConfigPort {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(HOLD_WINDOW_SECONDS, "120"),
            Map.entry(REQUIRE_VERIFY_CODE, "false"),
            Map.entry(VERIFY_CODE_TTL_SECONDS, "7200"),
            Map.entry(REQUIRE_PHOTO, "true"),
            Map.entry(FAMILY_MODE, "true"),
            Map.entry(MAX_TASKS_PER_WAVE, "8"),
            Map.entry(SERVICE_RADIUS_METERS, "3000"),
            Map.entry(STORE_LAT, "0"),
            Map.entry(STORE_LNG, "0"),
            Map.entry("dispatch.enabled", "true"),
            Map.entry("eta.default_handoff_seconds", "180"),
            Map.entry("tracking.retention_days", "90")
    );

    private final DeliveryTaskSupportDao supportDao;

    public JdbcDeliveryConfigPort(DeliveryTaskSupportDao supportDao) {
        this.supportDao = supportDao;
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
    public boolean getBoolean(String key) {
        String value = getString(key);
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()));
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

    @Override
    public String getString(String key) {
        Optional<String> stored = supportDao.configValue(key);
        if (stored.isPresent() && !stored.get().isBlank()) {
            return stored.get();
        }
        return DEFAULTS.get(key);
    }
}
