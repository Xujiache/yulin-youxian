package com.xianda.freshdelivery.delivery.tracking;

public interface TrackingConfigPort {
    String MAX_ACCURACY_METERS = "tracking.max_accuracy_meters";
    String GPS_SANITY_RADIUS_METERS = "store.gps_sanity_radius_meters";
    String REPORT_INTERVAL_STILL_SECONDS = "tracking.report_interval_still_seconds";
    String REPORT_INTERVAL_WALKING_SECONDS = "tracking.report_interval_walking_seconds";
    String REPORT_INTERVAL_RIDING_SECONDS = "tracking.report_interval_riding_seconds";
    String LIVENESS_TIMEOUT_SECONDS = "tracking.liveness_timeout_seconds";
    String RETENTION_DAYS = "tracking.retention_days";
    String ARRIVE_RADIUS_METERS = "geofence.arrive_radius_meters";
    String ARRIVE_DWELL_SECONDS = "geofence.arrive_dwell_seconds";
    String STORE_LAT = "store.lat";
    String STORE_LNG = "store.lng";
    String ETA_DISPLAY_AS_RANGE = "eta.display_as_range";
    String ETA_RANGE_SPAN_MINUTES = "eta.range_span_minutes";
    String PRIVACY_NUMBER_ENABLED = "privacy.number_enabled";
    String MAX_CONCURRENT_TASK = "dispatch.max_tasks_per_wave";
    String REQUIRE_VERIFY_CODE = "delivery.require_verify_code";
    String REQUIRE_PHOTO = "delivery.require_photo";
    String FATIGUE_WARN_SECONDS = "fatigue.continuous_warn_seconds";
    String FATIGUE_CONFIRM_SECONDS = "fatigue.daily_confirm_seconds";
    String FATIGUE_FORCE_SECONDS = "fatigue.daily_force_seconds";
    String MATRIX_PROVIDER = "routing.matrix_provider";
    String PUSH_PROVIDER = "push.provider";

    String getString(String key);

    int getInt(String key);

    boolean getBool(String key);

    double getDecimal(String key);
}
