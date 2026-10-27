package com.xianda.freshdelivery.delivery.dispatch;

import static com.xianda.freshdelivery.delivery.dispatch.DispatchTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReassignmentServiceTests {
    private DispatchFakes.MapConfigSource config;
    private DispatchSettings settings;
    private DispatchFakes.InMemoryDispatchDao dao;
    private DispatchFakes.RecordingAssignmentPort assignmentPort;
    private DispatchFakes.RecordingRoutingPort routingPort;
    private RiderScoringService scoringService;
    private ReassignmentService reassignmentService;
    private ManualDispatchService manualDispatchService;

    @BeforeEach
    void setUp() {
        config = DispatchTestSupport.defaultConfig();
        settings = DispatchTestSupport.settings(config);
        dao = new DispatchFakes.InMemoryDispatchDao();
        assignmentPort = new DispatchFakes.RecordingAssignmentPort(dao);
        routingPort = new DispatchFakes.RecordingRoutingPort();

        RouteEstimator estimator = new RouteEstimator(new DispatchFakes.FakeRoutePlanningPort(), settings);
        scoringService = new RiderScoringService(estimator, settings);
        BatchingService batchingService = new BatchingService(estimator, settings);
        reassignmentService = new ReassignmentService(dao, scoringService, estimator, assignmentPort,
                routingPort, settings);
        DispatchEngine engine = new DispatchEngine(dao, batchingService, new HoldingWindowService(settings),
                scoringService, reassignmentService, new CapacityAlertService(dao, scoringService, settings),
                new DispatchExplainer(new ObjectMapper()), assignmentPort, routingPort, settings,
                DispatchTestSupport.fixedClock());
        manualDispatchService = new ManualDispatchService(dao, engine, batchingService, scoringService,
                reassignmentService, assignmentPort, routingPort);
    }

    @Test
    void riskLevelsFollowTheDocumentedSlackBands() {
        assertEquals(RiskLevel.LOW, RiskLevel.of(901));
        assertEquals(RiskLevel.MEDIUM, RiskLevel.of(900));
        assertEquals(RiskLevel.MEDIUM, RiskLevel.of(301));
        assertEquals(RiskLevel.HIGH, RiskLevel.of(300));
        assertEquals(RiskLevel.HIGH, RiskLevel.of(1));
        assertEquals(RiskLevel.OVERTIME, RiskLevel.of(0));
        assertEquals(RiskLevel.OVERTIME, RiskLevel.of(-600));
    }

    @Test
    void aHighRiskTaskMovesToARiderWhoCanLowerTheRiskByAtLeastOneLevel() {
        givenBusyOwnerAndIdleRescuer();

        ReassignmentScanResult result = reassignmentService.scan(context());

        assertEquals(1, result.appliedCount());
        assertEquals(1, assignmentPort.reassigns().size());
        DispatchFakes.RecordingAssignmentPort.ReassignCall call = assignmentPort.reassigns().get(0);
        assertEquals(1L, call.taskId());
        assertEquals(2L, call.toRiderId());
        assertEquals("SYSTEM", call.operatorType());
        assertTrue(call.reason().contains(ReassignmentService.REASON_NO_PENALTY));
    }

    @Test
    void aTaskAlreadyReassignedTwiceIsLeftAloneToStopPingPong() {
        givenBusyOwnerAndIdleRescuer();
        dao.putTask(DispatchTestSupport.task(1)
                .status("ASSIGNED").rider(1L).wave(10L)
                .atMeters(9000)
                .dueAt(NOW.plusSeconds(120))
                .etaAt(NOW.plusSeconds(3000))
                .reassignCount(2)
                .build());

        ReassignmentScanResult result = reassignmentService.scan(context());

        assertEquals(0, result.appliedCount());
        assertTrue(assignmentPort.reassigns().isEmpty());
        assertTrue(result.decisions().get(0).reason().contains("达上限"));
    }

    @Test
    void aPickedUpTaskIsNeverAutoReassignedBecauseTheGoodsAreOnTheRider() {
        givenBusyOwnerAndIdleRescuer();
        dao.putTask(DispatchTestSupport.task(1)
                .status("PICKED_UP").rider(1L).wave(10L)
                .atMeters(9000)
                .dueAt(NOW.minusSeconds(600))
                .etaAt(NOW.plusSeconds(3000))
                .build());

        ReassignmentScanResult result = reassignmentService.scan(context());

        assertEquals(0, result.appliedCount());
        assertTrue(assignmentPort.reassigns().isEmpty());
        assertEquals("PICKED_UP", dao.task(1L).status());
    }

    @Test
    void manualReassignOfAPickedUpTaskIsRejectedWithAClearStatusError() {
        givenBusyOwnerAndIdleRescuer();
        dao.putTask(DispatchTestSupport.task(1).status("PICKED_UP").rider(1L).wave(10L).build());

        DeliveryException exception = assertThrows(DeliveryException.class,
                () -> manualDispatchService.reassign(1L, 2L, "骑手车坏了", "张调度"));

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertTrue(assignmentPort.reassigns().isEmpty());
    }

    @Test
    void turningOffAutoReassignLeavesTheDecisionToTheDispatcher() {
        givenBusyOwnerAndIdleRescuer();
        config.put(DispatchConfigKeys.AUTO_REASSIGN_ENABLED, false);

        ReassignmentScanResult result = reassignmentService.scan(context());

        assertEquals(0, result.appliedCount());
        assertEquals(1, result.overtimeRiskCount());
        assertTrue(result.decisions().get(0).reason().contains("auto_reassign_enabled"));
    }

    @Test
    void aLowRiskTaskIsNotTouchedAtAll() {
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).build());
        dao.putTask(DispatchTestSupport.task(1)
                .status("ACCEPTED").rider(1L).wave(10L)
                .atMeters(1000)
                .dueAt(NOW.plusSeconds(7200))
                .etaAt(NOW.plusSeconds(900))
                .build());

        ReassignmentScanResult result = reassignmentService.scan(context());

        assertEquals(0, result.appliedCount());
        assertEquals(0, result.overtimeRiskCount());
        assertTrue(result.decisions().isEmpty());
    }

    @Test
    void withNoBetterRiderTheTaskStaysPutAndIsCountedAsAnOvertimeRisk() {
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).fatiguePausedUntil(NOW.plusSeconds(1200)).build());
        dao.putTask(DispatchTestSupport.task(1)
                .status("ASSIGNED").rider(1L).wave(10L)
                .atMeters(9000)
                .dueAt(NOW.plusSeconds(60))
                .etaAt(NOW.plusSeconds(3000))
                .build());

        ReassignmentScanResult result = reassignmentService.scan(context());

        assertEquals(0, result.appliedCount());
        assertEquals(1, result.overtimeRiskCount());
        assertFalse(result.decisions().isEmpty());
        assertTrue(result.decisions().get(0).reason().contains("无骑手能把超时风险降低一级"));
    }

    @Test
    void aReassignmentBindsANewWaveAndPlansBothRidersRoutesAndEtas() {
        givenBusyOwnerAndIdleRescuer();

        reassignmentService.scan(context());

        Long newWaveId = dao.task(1L).waveId();
        assertTrue(newWaveId != null && newWaveId != 10L);
        assertTrue(routingPort.plannedWaves().contains(newWaveId));
        assertTrue(routingPort.etaWaves().contains(newWaveId));
        assertTrue(routingPort.replannedWaves().contains(10L));
        assertTrue(routingPort.etaWaves().contains(10L));
    }

    @Test
    void aFailedReassignmentDoesNotLeaveAnEmptyDestinationWave() {
        givenBusyOwnerAndIdleRescuer();
        assignmentPort.failWith(new DeliveryException(
                DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "状态被并发修改"));

        ReassignmentScanResult result = reassignmentService.scan(context());

        assertEquals(0, result.appliedCount());
        assertEquals(1L, dao.task(1L).riderId());
        assertEquals(10L, dao.task(1L).waveId());
        assertTrue(dao.waves().isEmpty());
    }

    @Test
    void 带时段的单改派后不会被塞进别的时段那趟车() {
        givenBusyOwnerAndIdleRescuer();
        dao.putTask(DispatchTestSupport.task(1)
                .status("ASSIGNED").rider(1L).wave(10L)
                .slotLabel("19:00-20:00")
                .atMeters(600)
                .dueAt(NOW.plusSeconds(1000))
                .etaAt(NOW.plusSeconds(950))
                .build());
        long afternoonWaveId = dao.createSlotWave(2L, java.time.LocalDate.from(NOW), "14:00-15:00", NOW);

        reassignmentService.scan(context());

        Long newWaveId = dao.task(1L).waveId();
        assertNotEquals(afternoonWaveId, newWaveId);
        assertEquals("19:00-20:00", dao.waveSlot(newWaveId));
    }

    @Test
    void 不带时段的单仍然并入目标骑手已有的波次() {
        givenBusyOwnerAndIdleRescuer();
        long existingWaveId = dao.createWave(2L, java.time.LocalDate.from(NOW), NOW);

        reassignmentService.scan(context());

        assertEquals(existingWaveId, dao.task(1L).waveId());
    }

    private void givenBusyOwnerAndIdleRescuer() {
        dao.putRider(DispatchTestSupport.rider(1).build());
        dao.putRider(DispatchTestSupport.rider(2).build());
        dao.putTask(DispatchTestSupport.task(1)
                .status("ASSIGNED").rider(1L).wave(10L)
                .atMeters(600)
                .dueAt(NOW.plusSeconds(1000))
                .etaAt(NOW.plusSeconds(950))
                .build());
        for (long taskId = 90; taskId < 95; taskId++) {
            dao.putTask(DispatchTestSupport.task(taskId)
                    .status("ACCEPTED").rider(1L).wave(10L)
                    .atMeters(4000)
                    .dueAt(NOW.plusSeconds(7200))
                    .etaAt(NOW.plusSeconds(3000))
                    .build());
        }
    }

    private DispatchContext context() {
        List<RiderCandidateRow> riders = dao.findRiderPool();
        Map<Long, List<DispatchTaskRow>> active = new java.util.HashMap<>();
        riders.forEach(rider -> active.put(rider.riderId(), dao.findActiveTasksByRider(rider.riderId())));
        return DispatchTestSupport.context(settings, riders, active, dao.countPendingTasks());
    }
}
