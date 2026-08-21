package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.dispatch.RoutePlanningPort;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingWithoutAmapKeyTests {
    private static final GeoPoint STORE = new GeoPoint(30.100000d, 120.700000d);

    private final RoutingFakes.InMemoryMatrixCacheDao cacheDao = new RoutingFakes.InMemoryMatrixCacheDao();
    private final RoutingSettings settings = RoutingTestSupport.settings(Map.of(
            RoutingConfigKeys.MATRIX_PROVIDER, "AMAP",
            RoutingConfigKeys.STORE_LAT, 30.100000d,
            RoutingConfigKeys.STORE_LNG, 120.700000d));
    private final HaversineMatrixProvider haversine = new HaversineMatrixProvider(settings);

    @Test
    void amapIsNotSelectedWhenDisabledOrKeyless() {
        assertFalse(amapProvider(false, "a-real-looking-key").available(), "amap.enabled=false 时不得被选中");
        assertFalse(amapProvider(true, "").available(), "缺少 Key 时不得被选中");
        assertFalse(amapProvider(true, null).available(), "Key 为 null 时不得被选中");
        assertTrue(amapProvider(true, "a-real-looking-key").available());
    }

    @Test
    void factoryFallsBackToHaversineWhenAmapIsConfiguredButUnavailable() {
        DistanceMatrixProviderFactory factory = new DistanceMatrixProviderFactory(
                List.of(haversine, amapProvider(false, "")), haversine, settings);

        assertSame(haversine, factory.resolve());
    }

    @Test
    void theWholePlanningChainRunsOnHaversineAlone() {
        RoutePlanningPort port = port();
        // 迟到惩罚拿门店时区的当前时间比对，这里也必须用同一个时钟：
        // 在 UTC 机器上用裸 LocalDateTime.now() 会让所有站点凭空迟到 8 小时，目标值直接被惩罚项打飞
        LocalDateTime windowEnd = RoutingTimes.now(RoutingTimes.systemClock()).plusHours(2);
        List<RoutePlanningPort.RouteStopInput> stops = new ArrayList<>();
        stops.add(stopInput(9001L, 30.105d, 120.705d, "FROZEN", windowEnd));
        stops.add(stopInput(9002L, 30.112d, 120.716d, "NORMAL", windowEnd));
        stops.add(stopInput(9003L, 30.108d, 120.709d, "CHILLED", windowEnd));
        stops.add(stopInput(9004L, 30.120d, 120.724d, "NORMAL", windowEnd));
        stops.add(stopInput(9005L, 30.103d, 120.702d, "NORMAL", windowEnd));
        stops.add(stopInput(9006L, 30.117d, 120.720d, "NORMAL", windowEnd));

        RoutePlanningPort.RoutePlanEstimate estimate = port.estimateRoute(STORE, stops);

        assertEquals(6, estimate.legs().size());
        assertTrue(estimate.totalDistanceMeters() > 0);
        assertTrue(estimate.totalDurationSeconds() > 0);
        assertTrue(estimate.objectiveValue() > 0d);
        assertEquals(0, cacheDao.size(), "Haversine 兜底不应写距离矩阵缓存表");

        int travelSeconds = 0;
        for (int i = 0; i < estimate.legs().size(); i++) {
            assertEquals(i + 1, estimate.legs().get(i).seqNo());
            assertTrue(estimate.legs().get(i).legDistanceMeters() > 0);
            travelSeconds += estimate.legs().get(i).legDurationSeconds();
        }
        assertEquals(travelSeconds + 6 * 180, estimate.totalDurationSeconds());
        assertEquals(travelSeconds, estimate.objectiveValue(), 1.0d, "该算例不触发任何惩罚项，目标值应等于纯行驶时长");

        long lastTaskId = estimate.legs().get(estimate.legs().size() - 1).taskId();
        assertEquals(9004L, lastTaskId, "最远站点应排在末位");
    }

    @Test
    void anEmptyStopListYieldsAnEmptyEstimateInsteadOfFailing() {
        RoutePlanningPort.RoutePlanEstimate estimate = port().estimateRoute(STORE, List.of());

        assertEquals(0, estimate.totalDistanceMeters());
        assertEquals(0, estimate.totalDurationSeconds());
        assertTrue(estimate.legs().isEmpty());
    }

    @Test
    void jspritIsRefusedWithAnExplicitUpgradeMessage() {
        RoutingSettings jspritSettings = RoutingTestSupport.settings(Map.of(RoutingConfigKeys.OPTIMIZER, "JSPRIT"));
        RouteOptimizerFactory factory = new RouteOptimizerFactory(
                new ExhaustiveOptimizer(jspritSettings), new GreedyTwoOptOptimizer(jspritSettings), jspritSettings);
        RouteProblem problem = RoutingTestSupport.problem(List.of(
                RoutingTestSupport.stop(1L, com.xianda.freshdelivery.delivery.common.ColdChainLevel.NORMAL, null, null)));

        com.xianda.freshdelivery.delivery.common.DeliveryException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.xianda.freshdelivery.delivery.common.DeliveryException.class,
                        () -> factory.resolve(problem, RoutingTestSupport.uniformMatrix(2, 100, 100)));

        assertEquals(JspritOptimizer.UNAVAILABLE_MESSAGE, thrown.getMessage());
    }

    private RoutePlanningPort port() {
        DistanceMatrixProviderFactory matrixFactory = new DistanceMatrixProviderFactory(
                List.of(haversine, amapProvider(false, "")), haversine, settings);
        MatrixCacheService cacheService = new MatrixCacheService(cacheDao, settings);
        RouteOptimizerFactory optimizerFactory = new RouteOptimizerFactory(
                new ExhaustiveOptimizer(settings), new GreedyTwoOptOptimizer(settings), settings);
        RouteSolverService solver = new RouteSolverService(matrixFactory, cacheService, optimizerFactory, settings);
        return new DefaultRoutePlanningPort(solver, settings);
    }

    private AmapMatrixProvider amapProvider(boolean enabled, String webKey) {
        DeliveryProperties properties = new DeliveryProperties(true,
                new DeliveryProperties.Store(30.1d, 120.7d),
                new DeliveryProperties.Amap(webKey, null, enabled),
                null, null, null, null, null);
        return new AmapMatrixProvider(new RoutingFakes.SingletonObjectProvider<>(properties), new ObjectMapper());
    }

    private RoutePlanningPort.RouteStopInput stopInput(long taskId, double lat, double lng, String coldChain,
                                                       LocalDateTime windowEnd) {
        return new RoutePlanningPort.RouteStopInput(taskId, new GeoPoint(lat, lng), coldChain, windowEnd, 180);
    }
}
