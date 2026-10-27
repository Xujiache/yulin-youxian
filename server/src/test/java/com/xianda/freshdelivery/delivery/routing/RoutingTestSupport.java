package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.common.ColdChainLevel;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

final class RoutingTestSupport {
    static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 11, 9, 0, 0);

    private RoutingTestSupport() {
    }

    static RoutingConfigSource configSource(Map<String, Object> values) {
        Map<String, Object> copy = new HashMap<>(values);
        return new RoutingConfigSource() {
            @Override
            public String getString(String key) {
                Object value = copy.get(key);
                return value == null ? null : String.valueOf(value);
            }

            @Override
            public Integer getInt(String key) {
                Object value = copy.get(key);
                return value == null ? null : ((Number) value).intValue();
            }

            @Override
            public Boolean getBool(String key) {
                Object value = copy.get(key);
                return value == null ? null : (Boolean) value;
            }

            @Override
            public BigDecimal getDecimal(String key) {
                Object value = copy.get(key);
                if (value == null) {
                    return null;
                }
                return value instanceof BigDecimal decimal ? decimal : BigDecimal.valueOf(((Number) value).doubleValue());
            }
        };
    }

    static RoutingSettings settings(Map<String, Object> values) {
        return new RoutingSettings(configSource(values), null);
    }

    static RoutingSettings defaultSettings() {
        return settings(Map.of());
    }

    static TravelMatrix linearMatrix(int[] positionsMeters, double speedMps) {
        int n = positionsMeters.length;
        int[][] distance = new int[n][n];
        int[][] duration = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int meters = Math.abs(positionsMeters[i] - positionsMeters[j]);
                distance[i][j] = meters;
                duration[i][j] = (int) Math.round(meters / speedMps);
            }
        }
        return new DistanceMatrixProvider.MatrixResult("TEST_LINEAR", TravelMode.EBIKE, distance, duration);
    }

    static TravelMatrix uniformMatrix(int nodeCount, int legMeters, int legSeconds) {
        int[][] distance = new int[nodeCount][nodeCount];
        int[][] duration = new int[nodeCount][nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                if (i == j) {
                    continue;
                }
                distance[i][j] = legMeters;
                duration[i][j] = legSeconds;
            }
        }
        return new DistanceMatrixProvider.MatrixResult("TEST_UNIFORM", TravelMode.EBIKE, distance, duration);
    }

    static RouteStop stop(long taskId, ColdChainLevel level, Long dueOffsetSeconds, String groupKey) {
        return new RouteStop(taskId, new GeoPoint(30.0 + taskId * 0.001, 120.0 + taskId * 0.001), level,
                null, dueOffsetSeconds == null ? null : T0.plusSeconds(dueOffsetSeconds),
                dueOffsetSeconds == null ? null : T0.plusSeconds(dueOffsetSeconds), 0, groupKey, null);
    }

    static RouteProblem problem(java.util.List<RouteStop> stops) {
        return new RouteProblem(new GeoPoint(30.0, 120.0), T0, stops, ObjectiveWeights.defaults(), 2000L);
    }
}
