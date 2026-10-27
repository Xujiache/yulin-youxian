package com.xianda.freshdelivery.delivery.task;

public interface DeliveryConfigPort {
    String HOLD_WINDOW_SECONDS = "dispatch.hold_window_seconds";
    String REQUIRE_VERIFY_CODE = "delivery.require_verify_code";
    String VERIFY_CODE_TTL_SECONDS = "delivery.verify_code_ttl_seconds";
    String REQUIRE_PHOTO = "delivery.require_photo";
    String FAMILY_MODE = "delivery.family_mode";
    String MAX_TASKS_PER_WAVE = "dispatch.max_tasks_per_wave";
    String SERVICE_RADIUS_METERS = "store.service_radius_meters";
    String STORE_LAT = "store.lat";
    String STORE_LNG = "store.lng";

    int getInt(String key);

    boolean getBoolean(String key);

    double getDecimal(String key);

    String getString(String key);
}
