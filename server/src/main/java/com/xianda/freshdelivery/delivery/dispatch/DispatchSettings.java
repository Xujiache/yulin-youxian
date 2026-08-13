package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.math.BigDecimal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class DispatchSettings {
    public static final double DEFAULT_WEIGHT_ADDED_DISTANCE = 0.35d;
    public static final double DEFAULT_WEIGHT_OVERTIME_RISK = 0.30d;
    public static final double DEFAULT_WEIGHT_LOAD_BALANCE = 0.15d;
    public static final double DEFAULT_WEIGHT_COLD_CHAIN = 0.15d;
    public static final double DEFAULT_WEIGHT_RIDER_LEVEL = 0.05d;
    public static final int SLACK_FULL_SCORE_SECONDS = 1800;
    public static final int SERVICE_SCORE_FLOOR = 60;
    public static final int ADDED_DISTANCE_HALF_SCORE_METERS = 1000;

    private final DispatchConfigSource configSource;
    private final ObjectProvider<DeliveryProperties> deliveryProperties;

    public DispatchSettings(DispatchConfigSource configSource, ObjectProvider<DeliveryProperties> deliveryProperties) {
        this.configSource = configSource;
        this.deliveryProperties = deliveryProperties;
    }

    public boolean dispatchEnabled() {
        return bool(DispatchConfigKeys.ENABLED, true);
    }

    /**
     * 派单模式，默认只推荐不执行。
     *
     * 和 {@link #dispatchEnabled()} 是两件事：ENABLED 是配送域自动化的总开关（关了连推荐都不算），
     * MODE 决定算出来的结果是自动落地还是等店主点。
     */
    public DispatchMode dispatchMode() {
        return DispatchMode.of(configSource.getString(DispatchConfigKeys.MODE));
    }

    /** 自动派单要同时满足「总开关开」和「模式是全自动」。 */
    public boolean autoDispatchAllowed() {
        return dispatchEnabled() && dispatchMode().isAuto();
    }

    public int holdWindowSeconds() {
        return Math.max(0, integer(DispatchConfigKeys.HOLD_WINDOW_SECONDS, 120));
    }

    public int maxTasksPerWave() {
        return Math.max(1, integer(DispatchConfigKeys.MAX_TASKS_PER_WAVE, 8));
    }

    public int maxWaveDistanceMeters() {
        return Math.max(1, integer(DispatchConfigKeys.MAX_WAVE_DISTANCE_METERS, 8000));
    }

    public int maxWaveDurationSeconds() {
        return Math.max(1, integer(DispatchConfigKeys.MAX_WAVE_DURATION_SECONDS, 3600));
    }

    public double maxWaveWeightKg() {
        return Math.max(0.001d, decimal(DispatchConfigKeys.MAX_WAVE_WEIGHT_KG, 30d));
    }

    public int batchingRadiusMeters() {
        return Math.max(0, integer(DispatchConfigKeys.BATCHING_RADIUS_METERS, 500));
    }

    public ScoreWeights weights() {
        return new ScoreWeights(
                decimal(DispatchConfigKeys.WEIGHT_ADDED_DISTANCE, DEFAULT_WEIGHT_ADDED_DISTANCE),
                decimal(DispatchConfigKeys.WEIGHT_OVERTIME_RISK, DEFAULT_WEIGHT_OVERTIME_RISK),
                decimal(DispatchConfigKeys.WEIGHT_LOAD_BALANCE, DEFAULT_WEIGHT_LOAD_BALANCE),
                decimal(DispatchConfigKeys.WEIGHT_COLD_CHAIN, DEFAULT_WEIGHT_COLD_CHAIN),
                decimal(DispatchConfigKeys.WEIGHT_RIDER_LEVEL, DEFAULT_WEIGHT_RIDER_LEVEL));
    }

    public int probationMaxTasks() {
        return Math.max(1, integer(DispatchConfigKeys.PROBATION_MAX_TASKS, 3));
    }

    public int probationMaxDistanceMeters() {
        return Math.max(0, integer(DispatchConfigKeys.PROBATION_MAX_DISTANCE_METERS, 2000));
    }

    /**
     * 自动改派同样要求全自动模式。
     *
     * 推荐模式下店主是自己挑的骑手，系统不能因为算出「有更优解」就把单换掉 ——
     * 原来这里只看开关不看模式，人工分好的单会被悄悄改派。
     */
    public boolean autoReassignEnabled() {
        return dispatchMode().isAuto() && bool(DispatchConfigKeys.AUTO_REASSIGN_ENABLED, true);
    }

    public int reassignMaxCount() {
        return Math.max(0, integer(DispatchConfigKeys.REASSIGN_MAX_COUNT, 2));
    }

    public double minScoreThreshold() {
        return decimal(DispatchConfigKeys.MIN_SCORE_THRESHOLD, 0d);
    }

    public int alertCooldownSeconds() {
        return Math.max(0, integer(DispatchConfigKeys.ALERT_COOLDOWN_SECONDS, 300));
    }

    public int serviceRadiusMeters() {
        return Math.max(0, integer(DispatchConfigKeys.STORE_SERVICE_RADIUS_METERS, 3000));
    }

    public int livenessTimeoutSeconds() {
        return Math.max(1, integer(DispatchConfigKeys.LIVENESS_TIMEOUT_SECONDS, 120));
    }

    public int pickupSeconds() {
        return Math.max(0, integer(DispatchConfigKeys.PICKUP_SECONDS, 300));
    }

    public int defaultHandoffSeconds() {
        return Math.max(0, integer(DispatchConfigKeys.DEFAULT_HANDOFF_SECONDS, 180));
    }

    public int serviceScoreCeiling() {
        int ceiling = integer(DispatchConfigKeys.SCORE_MAX, 120);
        return ceiling <= SERVICE_SCORE_FLOOR ? SERVICE_SCORE_FLOOR + 1 : ceiling;
    }

    public GeoPoint storeOrigin() {
        BigDecimal lat = configSource.getDecimal(DispatchConfigKeys.STORE_LAT);
        BigDecimal lng = configSource.getDecimal(DispatchConfigKeys.STORE_LNG);
        if (usable(lat) && usable(lng)) {
            return new GeoPoint(lat.doubleValue(), lng.doubleValue());
        }
        DeliveryProperties properties = deliveryProperties == null ? null : deliveryProperties.getIfAvailable();
        if (properties != null && properties.store() != null
                && properties.store().lat() != null && properties.store().lng() != null) {
            return new GeoPoint(properties.store().lat(), properties.store().lng());
        }
        return null;
    }

    private static boolean usable(BigDecimal value) {
        return value != null && value.signum() != 0;
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

    public record ScoreWeights(
            double addedDistance,
            double overtimeRisk,
            double loadBalance,
            double coldChain,
            double riderLevel
    ) {
        public double total() {
            return addedDistance + overtimeRisk + loadBalance + coldChain + riderLevel;
        }
    }
}
