package com.xianda.freshdelivery.delivery.routing;

public final class RoutingConfigKeys {
    public static final String STORE_LAT = "store.lat";
    public static final String STORE_LNG = "store.lng";
    public static final String AMAP_WEB_KEY = "amap.web_key";

    public static final String MATRIX_PROVIDER = "routing.matrix_provider";
    public static final String OPTIMIZER = "routing.optimizer";
    public static final String DETOUR_FACTOR = "routing.detour_factor";
    public static final String MATRIX_CACHE_HOURS = "routing.matrix_cache_hours";
    public static final String SOLVE_TIMEOUT_MILLIS = "routing.solve_timeout_millis";
    public static final String AMAP_MAX_REQUESTS = "routing.amap_max_requests";
    public static final String EXHAUSTIVE_MAX_STOPS = "routing.exhaustive_max_stops";
    public static final String LAMBDA_LATE = "routing.lambda_late";
    public static final String LAMBDA_COLD = "routing.lambda_cold";
    public static final String LAMBDA_EARLY = "routing.lambda_early";

    public static final String EBIKE_SPEED_KMH = "eta.ebike_speed_kmh";
    public static final String DEFAULT_HANDOFF_SECONDS = "eta.default_handoff_seconds";
    public static final String PICKUP_SECONDS = "eta.pickup_seconds";
    public static final String PROTECTION_FLOOR_SECONDS = "eta.protection_floor_seconds";
    public static final String QUANTILE = "eta.quantile";
    public static final String DISPLAY_AS_RANGE = "eta.display_as_range";
    public static final String RANGE_SPAN_MINUTES = "eta.range_span_minutes";
    public static final String BAD_WEATHER_ACTIVE = "eta.bad_weather_active";
    public static final String NIGHT_START_HOUR = "earning.night_start_hour";

    private RoutingConfigKeys() {
    }
}
