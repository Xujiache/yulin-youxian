package com.xianda.freshdelivery.delivery.dispatch;

import static com.xianda.freshdelivery.delivery.dispatch.DispatchTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchEngineTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DispatchFakes.MapConfigSource config;
    private DispatchSettings settings;
    private DispatchFakes.InMemoryDispatchDao dao;
    private DispatchFakes.RecordingAssignmentPort assignmentPort;
    private DispatchFakes.RecordingRoutingPort routingPort;
    private DispatchEngine engine;
    private ManualDispatchService manualDispatchService;

    @BeforeEach
    void setUp() {
        config = DispatchTestSupport.defaultConfig();
        settings = DispatchTestSupport.settings(config);
        dao = new DispatchFakes.InMemoryDispatchDao();
        assignmentPort = new DispatchFakes.RecordingAssignmentPort(dao);
        routingPort = new DispatchFakes.RecordingRoutingPort();

        RouteEstimator estimator = new RouteEstimator(new DispatchFakes.FakeRoutePlanningPort(), settings);
        RiderScoringService scoringService = new RiderScoringService(estimator, settings);
        BatchingService batchingService = new BatchingService(estimator, settings);
        HoldingWindowService holdingWindowService = new HoldingWindowService(settings);
        ReassignmentService reassignmentService = new ReassignmentService(
                dao, scoringService, estimator, assignmentPort, routingPort, settings);
        CapacityAlertService capacityAlertService = new CapacityAlertService(dao, scoringService, settings);
        DispatchExplainer explainer = new DispatchExplainer(MAPPER);
        engine = new DispatchEngine(dao, batchingService, holdingWindowService, scoringService,
                reassignmentService, capacityAlertService, explainer, assignmentPort, routingPort, settings,
                DispatchTestSupport.fixedClock());
        manualDispatchService = new ManualDispatchService(dao, engine, batchingService, scoringService,
                reassignmentService, assignmentPort, routingPort);
    }

    @Test
    void aFifteenKilometreOrderIsDispatchedBecauseSingleTaskWavesSkipTheDistanceCap() {
        dao.putTask(DispatchTestSupport.task(1).atMeters(15000).dueAt(NOW.plusSeconds(14400)).build());
        dao.putRider(DispatchTestSupport.rider(1).build());

        DispatchRoundResult result = engine.runOnce();

        assertEquals(1, result.assignedTaskCount());
        assertEquals(0, result.deferredTaskCount());
        assertEquals(1, result.waveCreatedCount());
        assertEquals("ASSIGNED", dao.task(1L).status());
        assertEquals(1L, dao.task(1L).riderId());
        assertNotNull(dao.task(1L).waveId());
    }

    @Test
    void theSoleDefaultProbationRiderStillReceivesAFifteenKilometreFamilyOrder() throws Exception {
        dao.putTask(DispatchTestSupport.task(1).atMeters(15000).dueAt(NOW.plusSeconds(14400)).build());
        dao.putRider(DispatchTestSupport.rider(1).probation(true).build());

        DispatchRoundResult result = engine.runOnce();

        assertEquals(1, result.assignedTaskCount());
        assertEquals(1L, dao.task(1L).riderId());
        JsonNode detail = MAPPER.readTree(assignmentPort.assigns().get(0).detailJson());
        assertTrue(warningsOf(detail.get("candidates").get(0))
                .contains(DispatchCodes.WARNING_FAMILY_FALLBACK));
    }

    @Test
    void aHighScoreThresholdDecaysAndCannotStrandAnOldPlatformOrderForever() {
        config.put(DispatchConfigKeys.MIN_SCORE_THRESHOLD, "0.99");
        dao.putTask(DispatchTestSupport.task(1)
                .atMeters(1500)
                .pickedReadyAt(NOW.minusSeconds(200))
                .build());
        dao.putRider(DispatchTestSupport.rider(1).build());

        DispatchRoundResult young = engine.runOnce();
        assertEquals(0, young.assignedTaskCount());
        assertEquals("PENDING", dao.task(1L).status());

        dao.putTask(DispatchTestSupport.task(1)
                .atMeters(1500)
                .pickedReadyAt(NOW.minusSeconds(
                        RiderScoringService.SCORE_FINAL_FALLBACK_AGE_SECONDS + 1))
                .build());
        DispatchRoundResult old = engine.runOnce();

        assertEquals(1, old.assignedTaskCount());
        assertTrue(assignmentPort.assigns().get(0).dispatchScore() < 0.99d);
    }

    @Test
    void aTwentyKilometreFrozenOrderGoesOutCarryingAColdChainWarning() throws Exception {
        dao.putTask(DispatchTestSupport.task(1)
                .atMeters(20000)
                .coldChain("FROZEN")
                .dueAt(NOW.plusSeconds(14400))
                .build());
        dao.putRider(DispatchTestSupport.rider(1).build());

        DispatchRoundResult result = engine.runOnce();

        assertEquals(1, result.assignedTaskCount());
        JsonNode detail = MAPPER.readTree(assignmentPort.assigns().get(0).detailJson());
        JsonNode selected = detail.get("candidates").get(0);
        assertTrue(selected.get("selected").asBoolean());
        assertTrue(warningsOf(selected).contains(DispatchCodes.WARNING_COLD_CHAIN_RISK));
        assertEquals(0d, selected.get("factors").get("coldChain").asDouble(), 1e-9);
    }

    @Test
    void whenEveryRiderIsFatiguePausedTheTaskStaysPendingAndRaisesACapacityAlert() {
        dao.putTask(DispatchTestSupport.task(1).build());
        dao.putRider(DispatchTestSupport.rider(1).fatiguePausedUntil(NOW.plusSeconds(1200)).build());
        dao.putRider(DispatchTestSupport.rider(2).fatiguePausedUntil(NOW.plusSeconds(1200)).build());

        DispatchRoundResult result = engine.runOnce();

        assertEquals(0, result.assignedTaskCount());
        assertEquals(1, result.deferredTaskCount());
        assertEquals("PENDING", dao.task(1L).status());
        assertTrue(assignmentPort.assigns().isEmpty());
        assertTrue(result.alerts().contains(DispatchCodes.ALERT_NO_CAPACITY));
        assertTrue(dao.messages().stream().anyMatch(message -> message.riderId() == null));
    }

    @Test
    void anUndeliverableRoundNeverDropsTheTaskItIsStillPendingNextRound() {
        dao.putTask(DispatchTestSupport.task(1).build());
        dao.putRider(DispatchTestSupport.rider(1).workStatus("OFF_DUTY").build());

        engine.runOnce();
        assertEquals("PENDING", dao.task(1L).status());

        dao.putRider(DispatchTestSupport.rider(1).build());
        DispatchRoundResult second = engine.runOnce();

        assertEquals(1, second.assignedTaskCount());
        assertEquals("ASSIGNED", dao.task(1L).status());
    }

    @Test
    void theDetailJsonCarriesTheWholeExplanationTheDispatchBoardRenders() throws Exception {
        dao.putTask(DispatchTestSupport.task(1)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501").atMeters(600).build());
        dao.putTask(DispatchTestSupport.task(2)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501").atMeters(600).build());
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).fatiguePausedUntil(NOW.plusSeconds(900)).build());

        engine.runOnce();

        JsonNode detail = MAPPER.readTree(assignmentPort.assigns().get(0).detailJson());
        assertEquals(2, detail.get("candidates").size());
        assertEquals(DispatchCodes.BATCH_SAME_ADDRESS, detail.get("batchReason").asText());
        assertEquals(2L, detail.get("batchedWith").get(0).asLong());
        assertTrue(detail.has("holdSeconds"));
        assertTrue(detail.has("waveId"));

        JsonNode winner = detail.get("candidates").get(0);
        assertTrue(winner.get("selected").asBoolean());
        assertTrue(winner.get("eligible").asBoolean());
        for (String key : new String[]{"addedDistance", "overtimeRisk", "loadBalance", "coldChain", "riderLevel"}) {
            assertTrue(winner.get("breakdown").has(key));
            assertTrue(winner.get("factors").has(key));
        }
        assertEquals(winner.get("score").asDouble(), sumOf(winner.get("breakdown")), 1e-6);

        JsonNode rejected = detail.get("candidates").get(1);
        assertFalse(rejected.get("selected").asBoolean());
        assertFalse(rejected.get("eligible").asBoolean());
        assertTrue(blockersOf(rejected).contains(DispatchCodes.BLOCKER_FATIGUE_PAUSED));
    }

    @Test
    void bothOrdersBehindOneDoorRideInTheSameWave() {
        dao.putTask(DispatchTestSupport.task(1)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501").atMeters(600).build());
        dao.putTask(DispatchTestSupport.task(2)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501").atMeters(600).build());
        dao.putRider(DispatchTestSupport.rider(1).build());

        DispatchRoundResult result = engine.runOnce();

        assertEquals(1, result.clusterCount());
        assertEquals(2, result.assignedTaskCount());
        assertEquals(dao.task(1L).waveId(), dao.task(2L).waveId());
    }

    @Test
    void aSuccessfulDispatchTriggersRoutePlanningAndEtaRecomputation() {
        dao.putTask(DispatchTestSupport.task(1).atMeters(900).build());
        dao.putRider(DispatchTestSupport.rider(1).build());

        engine.runOnce();

        Long waveId = dao.task(1L).waveId();
        assertTrue(routingPort.plannedWaves().contains(waveId));
        assertTrue(routingPort.etaWaves().contains(waveId));
    }

    @Test
    void aFarOrderNeverAppendsToAnExistingNearWave() {
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putTask(DispatchTestSupport.task(1).atMeters(800).build());
        engine.runOnce();
        Long nearWaveId = dao.task(1L).waveId();

        dao.putTask(DispatchTestSupport.task(2)
                .atMeters(15000)
                .dueAt(NOW.plusSeconds(14400))
                .build());
        DispatchRoundResult result = engine.runOnce();

        assertEquals(1, result.assignedTaskCount());
        assertFalse(nearWaveId.equals(dao.task(2L).waveId()));
        assertEquals(2, dao.waves().size());
    }

    @Test
    void aCompatibleNearOrderCanStillAppendToTheExistingWave() {
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putTask(DispatchTestSupport.task(1)
                .address("甲小区", "1号楼", "甲小区 1号楼 101室", "101")
                .atMeters(800)
                .build());
        engine.runOnce();
        Long waveId = dao.task(1L).waveId();

        dao.putTask(DispatchTestSupport.task(2)
                .address("乙小区", "2号楼", "乙小区 2号楼 201室", "201")
                .atMeters(900)
                .build());
        engine.runOnce();

        assertEquals(waveId, dao.task(2L).waveId());
        assertEquals(1, dao.waves().size());
        assertEquals(2, dao.waves().get(0).taskCount());
    }

    @Test
    void inFlightRiskIsScannedEvenWhenThereAreNoPendingCandidates() {
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).build());
        dao.putTask(DispatchTestSupport.task(1)
                .status("ASSIGNED")
                .rider(1L)
                .wave(10L)
                .atMeters(600)
                .dueAt(NOW.plusSeconds(1000))
                .etaAt(NOW.plusSeconds(950))
                .build());

        DispatchRoundResult result = engine.runOnce();

        assertEquals(0, result.candidateTaskCount());
        assertEquals(1, result.reassignedTaskCount());
        assertEquals(2L, dao.task(1L).riderId());
        assertNotNull(dao.task(1L).waveId());
    }

    @Test
    void aTaskStillInsideItsHoldWindowWaitsForTheNextRound() {
        dao.putTask(DispatchTestSupport.task(1)
                .atMeters(900)
                .dueAt(NOW.plusSeconds(7200))
                .pickedReadyAt(NOW.minusSeconds(30))
                .build());
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).build());
        dao.putRider(DispatchTestSupport.rider(3).build());

        DispatchRoundResult result = engine.runOnce();

        assertEquals(0, result.candidateTaskCount());
        assertEquals("PENDING", dao.task(1L).status());
    }

    @Test
    void manualForceCannotPushAnOrderOntoAFatiguePausedRider() {
        dao.putTask(DispatchTestSupport.task(1).build());
        dao.putRider(DispatchTestSupport.rider(1).fatiguePausedUntil(NOW.plusSeconds(1200)).build());

        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> manualDispatchService.assign(1L, 1L, true, "店长口头指派"));

        assertEquals(DeliveryErrorCode.RIDER_FATIGUE_SUSPENDED, exception.code());
        assertEquals("PENDING", dao.task(1L).status());
        assertTrue(assignmentPort.assigns().isEmpty());
    }

    @Test
    void manualForceStillWorksForAnOffDutyRiderBecauseThatIsNotAComplianceGate() {
        dao.putTask(DispatchTestSupport.task(1).atMeters(800).build());
        dao.putRider(DispatchTestSupport.rider(1).workStatus("RESTING").build());

        ManualDispatchResult result = manualDispatchService.assign(1L, 1L, true, "临时加急");

        assertEquals(1, result.assignedTaskIds().size());
        assertTrue(result.forced());
        assertEquals("ASSIGNED", dao.task(1L).status());
    }

    @Test
    void suggestReturnsCandidatesWithBreakdownBlockersAndBatchingHintWithoutTouchingTheDatabase() {
        dao.putTask(DispatchTestSupport.task(1)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501").atMeters(600).build());
        dao.putTask(DispatchTestSupport.task(2)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501").atMeters(600).build());
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).fatiguePausedUntil(NOW.plusSeconds(900)).build());

        DispatchSuggestResponse response = manualDispatchService.suggest(java.util.List.of(1L, 2L));

        assertEquals(2, response.suggestions().size());
        var first = response.suggestions().get(0);
        assertEquals(1L, first.taskId());
        assertEquals(2, first.candidates().size());
        assertNotNull(first.candidates().get(0).breakdown());
        assertEquals(1L, first.recommendedRiderId());
        assertEquals(java.util.List.of(2L), first.batchingHint().mergeWithTaskIds());
        assertEquals(DispatchCodes.BATCH_SAME_ADDRESS, first.batchingHint().reason());
        assertTrue(first.candidates().stream()
                .anyMatch(candidate -> candidate.blockers().contains(DispatchCodes.BLOCKER_FATIGUE_PAUSED)));
        assertTrue(assignmentPort.assigns().isEmpty());
        assertEquals("PENDING", dao.task(1L).status());
    }

    @Test
    void suggestClusterScoresTheWholeBatchAsOneWaveAndDoesNotAssign() {
        dao.putTask(DispatchTestSupport.task(1)
                .address("阳光小区", "3号楼", "阳光小区 3号楼 2单元 501室", "501").atMeters(600).build());
        dao.putTask(DispatchTestSupport.task(2)
                .address("兴顺苑", "1号楼", "兴顺苑A区 1号楼 1单元 602室", "602").atMeters(1800).build());
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).fatiguePausedUntil(NOW.plusSeconds(900)).build());

        var suggestion = manualDispatchService.suggestCluster(java.util.List.of(1L, 2L));

        assertEquals(1L, suggestion.taskId());
        assertEquals(java.util.List.of(2L), suggestion.batchingHint().mergeWithTaskIds());
        assertEquals(DispatchCodes.BATCH_SLOT_CLUSTER, suggestion.batchingHint().reason());
        assertEquals(1L, suggestion.recommendedRiderId());
        assertEquals(2, suggestion.candidates().size());
        assertTrue(assignmentPort.assigns().isEmpty());
        assertEquals("PENDING", dao.task(1L).status());
        assertEquals("PENDING", dao.task(2L).status());
    }

    @Test
    void aFailedAssignmentLeavesTheTaskInTheQueueRatherThanLosingIt() {
        dao.putTask(DispatchTestSupport.task(1).atMeters(800).build());
        dao.putRider(DispatchTestSupport.rider(1).build());
        assignmentPort.failWith(new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "状态已变更"));

        DispatchRoundResult result = engine.runOnce();

        assertEquals(0, result.assignedTaskCount());
        assertEquals(1, result.deferredTaskCount());
        assertEquals("PENDING", dao.task(1L).status());
        assertTrue(dao.waves().isEmpty());
    }

    @Test
    void concurrentAutomaticAndManualDispatchCreateOnlyOneMembership() throws Exception {
        dao.putTask(DispatchTestSupport.task(1).atMeters(800).build());
        dao.putRider(DispatchTestSupport.rider(1).build());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> automatic = executor.submit(() -> {
                await(start);
                engine.runOnce();
            });
            Future<?> manual = executor.submit(() -> {
                await(start);
                try {
                    manualDispatchService.assign(1L, 1L, false, "并发人工派单");
                } catch (DeliveryException ignored) {
                    // 自动派单先完成时，人工请求按状态 CAS 正常失败。
                }
            });
            start.countDown();
            automatic.get();
            manual.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals("ASSIGNED", dao.task(1L).status());
        assertEquals(1, assignmentPort.assigns().size());
        assertEquals(1, dao.waves().size());
        assertEquals(1, dao.waves().get(0).taskCount());
    }

    @Test
    void concurrentDispatchFailuresCleanEveryEmptyWave() throws Exception {
        dao.putTask(DispatchTestSupport.task(1).atMeters(800).build());
        dao.putRider(DispatchTestSupport.rider(1).build());
        assignmentPort.failWith(new DeliveryException(
                DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "并发状态已变更"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> automatic = executor.submit(() -> {
                await(start);
                engine.runOnce();
            });
            Future<?> manual = executor.submit(() -> {
                await(start);
                manualDispatchService.assign(1L, 1L, false, "并发人工派单");
            });
            start.countDown();
            automatic.get();
            manual.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals("PENDING", dao.task(1L).status());
        assertTrue(dao.waves().isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static double sumOf(JsonNode breakdown) {
        return breakdown.get("addedDistance").asDouble()
                + breakdown.get("overtimeRisk").asDouble()
                + breakdown.get("loadBalance").asDouble()
                + breakdown.get("coldChain").asDouble()
                + breakdown.get("riderLevel").asDouble();
    }

    private static java.util.List<String> warningsOf(JsonNode candidate) {
        return textList(candidate.get("warnings"));
    }

    private static java.util.List<String> blockersOf(JsonNode candidate) {
        return textList(candidate.get("blockers"));
    }

    private static java.util.List<String> textList(JsonNode array) {
        java.util.List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
