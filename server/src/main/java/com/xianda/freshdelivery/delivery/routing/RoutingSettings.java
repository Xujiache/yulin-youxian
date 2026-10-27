package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RoutingSettings {
    public static final double MAX_EBIKE_SPEED_KMH = 15.0d;
    public static final double PROTECTION_SPEED_KMH = 15.0d;
    public static final double PROTECTION_SLACK_FACTOR = 1.5d;

    private static final Logger log = LoggerFactory.getLogger(RoutingSettings.class);

    private final RoutingConfigSource configSource;
    private final ObjectProvider<DeliveryProperties> deliveryProperties;

    public RoutingSettings(RoutingConfigSource configSource, ObjectProvider<DeliveryProperties> deliveryProperties) {
        this.configSource = configSource;
        this.deliveryProperties = deliveryProperties;
    }

    public String matrixProvider() {
        return string(RoutingConfigKeys.MATRIX_PROVIDER, HaversineMatrixProvider.NAME);
    }

    public String optimizer() {
        return string(RoutingConfigKeys.OPTIMIZER, GreedyTwoOptOptimizer.NAME);
    }

    public double detourFactor() {
        return decimal(RoutingConfigKeys.DETOUR_FACTOR, 1.35d);
    }

    public int matrixCacheHours() {
        return integer(RoutingConfigKeys.MATRIX_CACHE_HOURS, 6);
    }

    public long solveTimeoutMillis() {
        return integer(RoutingConfigKeys.SOLVE_TIMEOUT_MILLIS, 2000);
    }

    public int amapMaxRequests() {
        return Math.max(1, Math.min(256, integer(RoutingConfigKeys.AMAP_MAX_REQUESTS, 64)));
    }

    public int exhaustiveMaxStops() {
        return integer(RoutingConfigKeys.EXHAUSTIVE_MAX_STOPS, 8);
    }

    public ObjectiveWeights objectiveWeights() {
        double late = decimal(RoutingConfigKeys.LAMBDA_LATE, ObjectiveWeights.DEFAULT_LATE);
        double cold = decimal(RoutingConfigKeys.LAMBDA_COLD, ObjectiveWeights.DEFAULT_COLD);
        double early = decimal(RoutingConfigKeys.LAMBDA_EARLY, ObjectiveWeights.DEFAULT_EARLY);
        if (cold < late) {
            log.warn("routing.lambda_cold={} 小于 routing.lambda_late={}，已提升至同值以保住冷链优先", cold, late);
            cold = late;
        }
        return new ObjectiveWeights(late, cold, early);
    }

    public double ebikeSpeedKmh() {
        double configured = decimal(RoutingConfigKeys.EBIKE_SPEED_KMH, MAX_EBIKE_SPEED_KMH);
        return clampEbikeSpeedKmh(configured);
    }

    public double ebikeSpeedMps() {
        return ebikeSpeedKmh() / 3.6d;
    }

    public int defaultHandoffSeconds() {
        return integer(RoutingConfigKeys.DEFAULT_HANDOFF_SECONDS, 180);
    }

    public int pickupSeconds() {
        return integer(RoutingConfigKeys.PICKUP_SECONDS, 300);
    }

    public int protectionFloorSeconds() {
        return integer(RoutingConfigKeys.PROTECTION_FLOOR_SECONDS, 1200);
    }

    public double quantile() {
        return decimal(RoutingConfigKeys.QUANTILE, 0.70d);
    }

    public boolean displayAsRange() {
        return bool(RoutingConfigKeys.DISPLAY_AS_RANGE, true);
    }

    public int rangeSpanMinutes() {
        return integer(RoutingConfigKeys.RANGE_SPAN_MINUTES, 10);
    }

    public boolean badWeatherActive() {
        return bool(RoutingConfigKeys.BAD_WEATHER_ACTIVE, false);
    }

    public int nightStartHour() {
        return integer(RoutingConfigKeys.NIGHT_START_HOUR, 20);
    }

    public GeoPoint storeOrigin() {
        BigDecimal lat = configSource.getDecimal(RoutingConfigKeys.STORE_LAT);
        BigDecimal lng = configSource.getDecimal(RoutingConfigKeys.STORE_LNG);
        if (isUsable(lat) && isUsable(lng)) {
            return new GeoPoint(lat.doubleValue(), lng.doubleValue());
        }
        DeliveryProperties properties = deliveryProperties == null ? null : deliveryProperties.getIfAvailable();
        if (properties != null && properties.store() != null
                && properties.store().lat() != null && properties.store().lng() != null) {
            return new GeoPoint(properties.store().lat(), properties.store().lng());
        }
        return null;
    }

    public static double clampEbikeSpeedKmh(double configured) {
        if (configured <= 0d) {
            return MAX_EBIKE_SPEED_KMH;
        }
        if (configured > MAX_EBIKE_SPEED_KMH) {
            log.warn("eta.ebike_speed_kmh 配置为 {}，超过 GB/T 46862-2025 上限 {}，已钳制", configured, MAX_EBIKE_SPEED_KMH);
            return MAX_EBIKE_SPEED_KMH;
        }
        return configured;
    }

    private boolean isUsable(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    private String string(String key, String fallback) {
        String value = configSource.getString(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int integer(String key, int fallback) {
        Integer value = configSource.getInt(key);
        return value == null ? fallback : value;
    }

    private boolean bool(String key, boolean fallback) {
        Boolean value = configSource.getBool(key);
        return value == null ? fallback : value;
    }

    private double decimal(String key, double fallback) {
        BigDecimal value = configSource.getDecimal(key);
        return value == null ? fallback : value.doubleValue();
    }
}
