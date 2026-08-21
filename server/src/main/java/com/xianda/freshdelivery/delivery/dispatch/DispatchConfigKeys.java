package com.xianda.freshdelivery.delivery.dispatch;

public final class DispatchConfigKeys {
    public static final String ENABLED = "dispatch.enabled";
    /** ADVISORY / AUTO。见 {@link DispatchMode}。 */
    public static final String MODE = "dispatch.mode";
    public static final String HOLD_WINDOW_SECONDS = "dispatch.hold_window_seconds";
    public static final String LOOP_INTERVAL_SECONDS = "dispatch.loop_interval_seconds";
    public static final String MAX_TASKS_PER_WAVE = "dispatch.max_tasks_per_wave";
    public static final String MAX_WAVE_DISTANCE_METERS = "dispatch.max_wave_distance_meters";
    public static final String MAX_WAVE_DURATION_SECONDS = "dispatch.max_wave_duration_seconds";
    public static final String MAX_WAVE_WEIGHT_KG = "dispatch.max_wave_weight_kg";
    public static final String BATCHING_RADIUS_METERS = "dispatch.batching_radius_meters";
    public static final String WEIGHT_ADDED_DISTANCE = "dispatch.weight_added_distance";
    public static final String WEIGHT_OVERTIME_RISK = "dispatch.weight_overtime_risk";
    public static final String WEIGHT_LOAD_BALANCE = "dispatch.weight_load_balance";
    public static final String WEIGHT_COLD_CHAIN = "dispatch.weight_cold_chain";
    public static final String WEIGHT_RIDER_LEVEL = "dispatch.weight_rider_level";
    public static final String PROBATION_MAX_TASKS = "dispatch.probation_max_tasks";
    public static final String PROBATION_MAX_DISTANCE_METERS = "dispatch.probation_max_distance_meters";
    public static final String AUTO_REASSIGN_ENABLED = "dispatch.auto_reassign_enabled";
    public static final String REASSIGN_MAX_COUNT = "dispatch.reassign_max_count";
    public static final String MIN_SCORE_THRESHOLD = "dispatch.min_score_threshold";
    public static final String ALERT_COOLDOWN_SECONDS = "dispatch.alert_cooldown_seconds";

    public static final String STORE_LAT = "store.lat";
    public static final String STORE_LNG = "store.lng";
    public static final String STORE_SERVICE_RADIUS_METERS = "store.service_radius_meters";

    public static final String LIVENESS_TIMEOUT_SECONDS = "tracking.liveness_timeout_seconds";
    public static final String PICKUP_SECONDS = "eta.pickup_seconds";
    public static final String DEFAULT_HANDOFF_SECONDS = "eta.default_handoff_seconds";
    public static final String SCORE_MAX = "score.max";

    private DispatchConfigKeys() {
    }
}
