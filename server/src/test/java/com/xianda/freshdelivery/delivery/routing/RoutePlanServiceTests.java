package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import com.xianda.freshdelivery.delivery.domain.RoutePlan;
import com.xianda.freshdelivery.delivery.dto.WaveRouteDto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class RoutePlanServiceTests {
    private static final long WAVE_ID = 601L;
    private static final long RIDER_ID = 7L;
    private static final LocalDateTime WAVE_START = LocalDateTime.of(2026, 8, 11, 9, 0, 0);

    private final RoutingFakes.InMemoryWaveDao waveDao = new RoutingFakes.InMemoryWaveDao();
    private final RoutingFakes.InMemoryRoutePlanDao planDao = new RoutingFakes.InMemoryRoutePlanDao();
    private final RoutingFakes.RecordingEtaDao etaDao = new RoutingFakes.RecordingEtaDao();
    private final RoutingFakes.InMemoryHandoffStatDao handoffStatDao = new RoutingFakes.InMemoryHandoffStatDao();
    private final RoutingFakes.InMemoryMatrixCacheDao cacheDao = new RoutingFakes.InMemoryMatrixCacheDao();
    private final RoutingSettings settings = RoutingTestSupport.settings(Map.of(
            RoutingConfigKeys.STORE_LAT, 30.100000d,
            RoutingConfigKeys.STORE_LNG, 120.700000d));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RoutePlanService service;

    @BeforeEach
    void setUp() {
        HaversineMatrixProvider haversine = new HaversineMatrixProvider(settings);
        HandoffEstimator handoffEstimator = new HandoffEstimator(handoffStatDao, settings);
        RouteSolverService solver = new RouteSolverService(
                new DistanceMatrixProviderFactory(List.of(haversine), haversine, settings),
                new MatrixCacheService(cacheDao, settings),
                new RouteOptimizerFactory(new ExhaustiveOptimizer(settings),
                        new GreedyTwoOptOptimizer(settings), settings),
                settings);
        EtaEngine etaEngine = new EtaEngine(waveDao, etaDao, handoffEstimator, at -> false, settings);
        service = new RoutePlanService(waveDao, planDao, solver, handoffEstimator, haversine, etaEngine,
                settings, objectMapper);
        seedWave();
    }

    @Test
    void initialPlanWritesAnActiveVersionOneWithContractShapedSequenceJson() throws Exception {
        RoutePlanService.PlanResult result = service.plan(WAVE_ID, ReplanTrigger.INITIAL);

        assertEquals(1, result.planVersion());
        assertEquals(ReplanTrigger.INITIAL, result.trigger());
        assertEquals(HaversineMatrixProvider.NAME, result.solution().matrixProvider());
        assertTrue(result.totalDistanceMeters() > 0);
        assertNotNull(result.planReturnAt());

        RoutePlan stored = planDao.findActive(WAVE_ID).orElseThrow();
        assertEquals(4, stored.stopCount());
        assertTrue(stored.polyline() != null && !stored.polyline().isBlank());
        JsonNode sequence = objectMapper.readTree(stored.sequenceJson());
        assertEquals(4, sequence.size());
        for (int i = 0; i < sequence.size(); i++) {
            assertEquals(i + 1, sequence.get(i).get("seq").asInt());
            assertTrue(sequence.get(i).hasNonNull("taskId"));
            assertTrue(sequence.get(i).hasNonNull("etaAt"));
        }
    }

    @Test
    void replanIncrementsTheVersionAndRetiresThePreviousActiveRow() {
        service.plan(WAVE_ID, ReplanTrigger.INITIAL);
        RoutePlanService.PlanResult second = service.plan(WAVE_ID, ReplanTrigger.NEW_TASK);

        assertEquals(2, second.planVersion());
        assertEquals(2, planDao.plans().size());
        assertFalse(planDao.plans().get(0).isActive());
        assertTrue(planDao.plans().get(1).isActive());
        assertEquals(ReplanTrigger.NEW_TASK.name(), planDao.plans().get(1).triggerReason());
    }

    @Test
    void waveStopsAndOriginalSequenceAreKeptInSyncWithThePlan() {
        service.plan(WAVE_ID, ReplanTrigger.INITIAL);
        List<RoutingStopRow> firstPass = List.copyOf(waveDao.upsertedStops());
        service.plan(WAVE_ID, ReplanTrigger.MANUAL);

        assertEquals(4, firstPass.size());
        for (RoutingStopRow stop : firstPass) {
            assertEquals(stop.seqNo(), stop.originalSeqNo());
            assertNotNull(stop.planArriveAt());
            assertNotNull(stop.planDepartAt());
            assertTrue(stop.handoffEstimateSeconds() > 0);
            assertFalse(stop.adjustedByRider());
        }
        List<RoutingStopRow> secondPass = waveDao.upsertedStops().subList(4, 8);
        for (RoutingStopRow stop : secondPass) {
            RoutingStopRow original = firstPass.stream()
                    .filter(row -> row.taskId() == stop.taskId())
                    .findFirst()
                    .orElseThrow();
            assertEquals(original.originalSeqNo(), stop.originalSeqNo(), "原始顺序应保留用于学习骑手偏好");
        }
    }

    @Test
    void riderReorderingOnlyRefreshesEtaAndNeverCreatesANewPlan() {
        service.plan(WAVE_ID, ReplanTrigger.INITIAL);
        int plansAfterInitial = planDao.plans().size();
        etaDao.etas().clear();

        service.recomputeEtaAfterRiderAdjustment(WAVE_ID);

        assertEquals(plansAfterInitial, planDao.plans().size(), "骑手手动调序不得触发重规划");
        assertEquals(4, etaDao.etas().size());
    }

    @Test
    void riderFacingRouteMatchesTheApiContractAndIsOwnershipChecked() {
        service.plan(WAVE_ID, ReplanTrigger.INITIAL);

        WaveRouteDto route = service.routeForRider(WAVE_ID, RIDER_ID);

        assertEquals(WAVE_ID, route.waveId());
        assertEquals(1, route.planVersion());
        assertEquals(HaversineMatrixProvider.NAME, route.matrixProvider());
        assertEquals(RoutePlanService.STORE_DISPLAY_NAME, route.origin().name());
        assertEquals(30.100000d, route.origin().lat(), 1e-9);
        assertEquals(4, route.stops().size());
        assertEquals(1, route.stops().get(0).seqNo());
        assertNotNull(route.stops().get(0).location());
        assertNotNull(route.polyline());

        DeliveryException thrown = assertThrows(DeliveryException.class, () -> service.routeForRider(WAVE_ID, 999L));
        assertEquals(1012, thrown.code());
    }

    @Test
    void deliveredStopsStayPinnedAtTheFrontOfAReplan() {
        waveDao.upsertStop(new RoutingStopRow(WAVE_ID, 9004L, 1, 1, 30.130000d, 120.734000d,
                900, 216, 180, WAVE_START, WAVE_START.plusMinutes(5), WAVE_START, WAVE_START.plusMinutes(5), false));
        waveDao.putTask(new RoutingTaskRow(9004L, WAVE_ID, "DELIVERED", 30.130000d, 120.734000d, "NORMAL",
                null, WAVE_START.plusHours(3), WAVE_START.plusHours(3), null, null, null, null,
                0, null, 300, WAVE_START, WAVE_START, WAVE_START.plusMinutes(5)));

        RoutePlanService.PlanResult result = service.plan(WAVE_ID, ReplanTrigger.NEW_TASK);
        List<RoutingStopRow> stops = waveDao.upsertedStops();

        assertEquals(4, result.solution().legs().size() + 1);
        RoutingStopRow first = stops.stream().filter(row -> row.seqNo() == 1).findFirst().orElseThrow();
        assertEquals(9004L, first.taskId(), "已送达的站点必须钉在序列最前");
    }

    @Test
    void deviationKeepsTheRiderSequenceAndOnlyRefreshesLegsAndEta() {
        service.plan(WAVE_ID, ReplanTrigger.INITIAL);
        List<Long> plannedOrder = orderedTaskIds();
        List<Long> riderOrder = new java.util.ArrayList<>(plannedOrder);
        java.util.Collections.reverse(riderOrder);
        applyRiderOrder(riderOrder);

        RoutePlanService.PlanResult result = service.plan(WAVE_ID, ReplanTrigger.DEVIATION);

        assertEquals(RouteSolverService.FIXED_SEQUENCE, result.solution().optimizerName());
        assertEquals(riderOrder, result.solution().taskIdSequence(), "偏离触发时不得强行纠正骑手已选的顺序");
        assertEquals(ReplanTrigger.DEVIATION.name(), planDao.findActive(WAVE_ID).orElseThrow().triggerReason());
        assertTrue(result.totalDistanceMeters() > 0);
    }

    @Test
    void suspendedTasksArePulledOutOfTheDeliverySequenceButKeepTheirStopRow() {
        waveDao.putTask(new RoutingTaskRow(9003L, WAVE_ID, "EXCEPTION", 30.107000d, 120.708000d, "NORMAL",
                null, WAVE_START.plusHours(3), WAVE_START.plusHours(3), "小区2", "小区2", "2号楼", 3,
                0, null, null, WAVE_START, null, null));

        RoutePlanService.PlanResult result = service.plan(WAVE_ID, ReplanTrigger.EXCEPTION);

        assertEquals(3, result.solution().legs().size());
        assertFalse(result.solution().taskIdSequence().contains(9003L));
        RoutingStopRow parked = waveDao.upsertedStops().stream()
                .filter(stop -> stop.taskId() == 9003L)
                .findFirst()
                .orElseThrow();
        assertEquals(4, parked.seqNo(), "挂起任务应排在活跃序列之后，保持 seq_no 唯一");
        assertEquals(0, parked.legDistanceMeters());
    }

    @Test
    void externalMatrixWorkRunsBeforeTheWriteTransactionStarts() {
        RoutingSettings transactionSettings = RoutingTestSupport.settings(Map.of(
                RoutingConfigKeys.STORE_LAT, 30.100000d,
                RoutingConfigKeys.STORE_LNG, 120.700000d,
                RoutingConfigKeys.MATRIX_PROVIDER, "TX_ASSERT"));
        HaversineMatrixProvider haversine = new HaversineMatrixProvider(transactionSettings);
        AtomicBoolean called = new AtomicBoolean();
        DistanceMatrixProvider assertingProvider = new DistanceMatrixProvider() {
            @Override
            public String name() {
                return "TX_ASSERT";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public MatrixResult compute(GeoPoint origin, List<GeoPoint> destinations, TravelMode mode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MatrixResult computeFull(List<GeoPoint> points, TravelMode mode) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                        "外部路线 HTTP/矩阵调用不得持有数据库事务");
                called.set(true);
                return haversine.computeFull(points, mode);
            }
        };
        RouteSolverService solver = new RouteSolverService(
                new DistanceMatrixProviderFactory(List.of(assertingProvider, haversine), haversine,
                        transactionSettings),
                new MatrixCacheService(cacheDao, transactionSettings),
                new RouteOptimizerFactory(new ExhaustiveOptimizer(transactionSettings),
                        new GreedyTwoOptOptimizer(transactionSettings), transactionSettings),
                transactionSettings);
        HandoffEstimator estimator = new HandoffEstimator(handoffStatDao, transactionSettings);
        EtaEngine etaEngine = new EtaEngine(waveDao, etaDao, estimator, at -> false, transactionSettings);
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:routing_tx;DB_CLOSE_DELAY=-1");
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        RoutePlanService transactionalService = new RoutePlanService(
                waveDao, planDao, solver, estimator, haversine, etaEngine, transactionSettings, objectMapper,
                transactionTemplate, Clock.fixed(Instant.parse("2026-08-11T01:00:00Z"), ZoneOffset.UTC));

        transactionalService.plan(WAVE_ID, ReplanTrigger.INITIAL);

        assertTrue(called.get());
    }

    private List<Long> orderedTaskIds() {
        return waveDao.findStops(WAVE_ID).stream()
                .sorted(java.util.Comparator.comparingInt(RoutingStopRow::seqNo))
                .map(RoutingStopRow::taskId)
                .toList();
    }

    private void applyRiderOrder(List<Long> taskIds) {
        for (int i = 0; i < taskIds.size(); i++) {
            long taskId = taskIds.get(i);
            RoutingStopRow current = waveDao.findStops(WAVE_ID).stream()
                    .filter(stop -> stop.taskId() == taskId)
                    .findFirst()
                    .orElseThrow();
            waveDao.upsertStop(new RoutingStopRow(WAVE_ID, taskId, i + 1, current.originalSeqNo(),
                    current.lat(), current.lng(), current.legDistanceMeters(), current.legDurationSeconds(),
                    current.handoffEstimateSeconds(), current.planArriveAt(), current.planDepartAt(),
                    null, null, true));
        }
    }

    private void seedWave() {
        waveDao.putWave(new RoutingWaveRow(WAVE_ID, "BC202608110001", RIDER_ID, "ASSIGNED",
                WAVE_START.minusMinutes(10), WAVE_START, null));
        double[][] coordinates = {
                {30.104000d, 120.704000d},
                {30.112000d, 120.716000d},
                {30.107000d, 120.708000d},
                {30.130000d, 120.734000d}};
        long[] taskIds = {9001L, 9002L, 9003L, 9004L};
        for (int i = 0; i < taskIds.length; i++) {
            waveDao.putTask(new RoutingTaskRow(taskIds[i], WAVE_ID, "ACCEPTED",
                    coordinates[i][0], coordinates[i][1], i == 0 ? "FROZEN" : "NORMAL",
                    null, WAVE_START.plusHours(3), WAVE_START.plusHours(3),
                    "小区" + i, "小区" + i, i + "号楼", 3, 0, null, null, WAVE_START, null, null));
        }
    }
}
