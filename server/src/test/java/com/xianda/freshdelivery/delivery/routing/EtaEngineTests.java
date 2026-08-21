package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EtaEngineTests {
    private static final long WAVE_ID = 501L;
    private static final LocalDateTime WAVE_START = LocalDateTime.of(2026, 8, 11, 9, 0, 0);
    private static final long[] TASK_IDS = {9001L, 9002L, 9003L, 9004L};

    private final RoutingFakes.InMemoryWaveDao waveDao = new RoutingFakes.InMemoryWaveDao();
    private final RoutingFakes.RecordingEtaDao etaDao = new RoutingFakes.RecordingEtaDao();
    private final RoutingFakes.InMemoryHandoffStatDao handoffStatDao = new RoutingFakes.InMemoryHandoffStatDao();
    private final RoutingSettings settings = RoutingTestSupport.settings(Map.of(
            RoutingConfigKeys.STORE_LAT, 30.100000d,
            RoutingConfigKeys.STORE_LNG, 120.700000d));
    private final EtaEngine engine = new EtaEngine(waveDao, etaDao,
            new HandoffEstimator(handoffStatDao, settings), at -> false, settings);

    @Test
    void pickingDelayCascadesToEveryLaterStopInTheWave() {
        seedWave(Map.of());

        engine.applyPickingDelay(TASK_IDS[1], 600);

        List<RoutingFakes.RecordingEtaDao.ExtraTimeCall> calls = etaDao.extraTimeCalls();
        assertEquals(3, calls.size(), "应补时 3 单：延迟单本身与其后两单");
        assertEquals(TASK_IDS[1], calls.get(0).taskId());
        assertEquals(EtaEngine.PICKING_DELAY_REASON, calls.get(0).reason());
        assertEquals(600, calls.get(0).deltaSeconds());
        assertEquals(TASK_IDS[2], calls.get(1).taskId());
        assertEquals(EtaEngine.CASCADE_REASON, calls.get(1).reason());
        assertEquals(TASK_IDS[3], calls.get(2).taskId());
        assertEquals(EtaEngine.CASCADE_REASON, calls.get(2).reason());
        assertFalse(calls.stream().anyMatch(call -> call.taskId() == TASK_IDS[0]), "前序已完成单不应被补时");
    }

    @Test
    void cascadedExtraTimeShiftsTheEtaOfEveryLaterStop() {
        seedWave(Map.of());
        Map<Long, LocalDateTime> before = etaByTask(engine.computeWaveEta(WAVE_ID));

        RoutingFakes.InMemoryWaveDao delayed = new RoutingFakes.InMemoryWaveDao();
        seedWave(delayed, Map.of(TASK_IDS[1], 600, TASK_IDS[2], 600, TASK_IDS[3], 600));
        EtaEngine delayedEngine = new EtaEngine(delayed, etaDao,
                new HandoffEstimator(handoffStatDao, settings), at -> false, settings);
        Map<Long, LocalDateTime> after = etaByTask(delayedEngine.computeWaveEta(WAVE_ID));

        assertEquals(0L, secondsBetween(before.get(TASK_IDS[0]), after.get(TASK_IDS[0])));
        assertEquals(600L, secondsBetween(before.get(TASK_IDS[1]), after.get(TASK_IDS[1])));
        assertEquals(600L, secondsBetween(before.get(TASK_IDS[2]), after.get(TASK_IDS[2])));
        assertEquals(600L, secondsBetween(before.get(TASK_IDS[3]), after.get(TASK_IDS[3])));
    }

    @Test
    void etaGrowsMonotonicallyAlongTheSequenceAndIsWrittenAsARange() {
        seedWave(Map.of());

        engine.recomputeWaveEta(WAVE_ID);

        assertEquals(4, etaDao.etas().size());
        LocalDateTime previous = null;
        for (long taskId : TASK_IDS) {
            LocalDateTime eta = etaDao.etas().get(taskId);
            assertTrue(eta != null && eta.isAfter(WAVE_START));
            if (previous != null) {
                assertTrue(!eta.isBefore(previous), "后续站点的 ETA 不应早于前序站点");
            }
            previous = eta;
        }
    }

    @Test
    void aTaskThatCannotMakeItsWindowIsFlaggedButNeverRejected() {
        seedWave(Map.of());
        EtaEngine.WaveEtaResult result = engine.computeWaveEta(WAVE_ID);

        RoutingFakes.InMemoryWaveDao tight = new RoutingFakes.InMemoryWaveDao();
        seedWave(tight, Map.of(), WAVE_START.plusMinutes(5));
        EtaEngine tightEngine = new EtaEngine(tight, etaDao,
                new HandoffEstimator(handoffStatDao, settings), at -> false, settings);
        EtaEngine.WaveEtaResult tightResult = tightEngine.computeWaveEta(WAVE_ID);

        assertEquals(4, result.tasks().size());
        assertEquals(4, tightResult.tasks().size());
        assertTrue(tightResult.tasks().stream().allMatch(EtaEngine.TaskEta::promiseAtRisk));
        assertTrue(tightResult.tasks().stream().allMatch(task -> task.overtimeRiskSeconds() > 0));
    }

    @Test
    void etaWaitsForWindowStartAndCarriesThatWaitToLaterStops() {
        seedWave(Map.of());
        RoutingTaskRow first = waveDao.findTask(TASK_IDS[0]).orElseThrow();
        LocalDateTime notBefore = WAVE_START.plusHours(1);
        waveDao.putTask(withWindowStart(first, notBefore));

        EtaEngine.WaveEtaResult result = engine.computeWaveEta(WAVE_ID);
        LocalDateTime firstEta = result.tasks().get(0).eta().etaAt();
        LocalDateTime secondEta = result.tasks().get(1).eta().etaAt();

        assertFalse(firstEta.isBefore(notBefore));
        assertFalse(secondEta.isBefore(firstEta), "首站等待必须级联到后续站点");
    }

    @Test
    void startedWaveDoesNotChargePickupTimeAgain() {
        seedWave(Map.of());
        long startedModelSeconds = engine.computeWaveEta(WAVE_ID).tasks().get(0).eta().modelSeconds();

        RoutingFakes.InMemoryWaveDao notStarted = new RoutingFakes.InMemoryWaveDao();
        seedWave(notStarted, Map.of());
        notStarted.putWave(new RoutingWaveRow(
                WAVE_ID, "BC202608110001", 7L, "ASSIGNED", WAVE_START, null, null));
        EtaEngine notStartedEngine = new EtaEngine(notStarted, etaDao,
                new HandoffEstimator(handoffStatDao, settings), at -> false, settings);
        long notStartedModelSeconds =
                notStartedEngine.computeWaveEta(WAVE_ID).tasks().get(0).eta().modelSeconds();

        assertEquals(settings.pickupSeconds(), notStartedModelSeconds - startedModelSeconds);
    }

    private void seedWave(Map<Long, Integer> extraTimeByTask) {
        seedWave(waveDao, extraTimeByTask, WAVE_START.plusHours(3));
    }

    private void seedWave(RoutingFakes.InMemoryWaveDao dao, Map<Long, Integer> extraTimeByTask) {
        seedWave(dao, extraTimeByTask, WAVE_START.plusHours(3));
    }

    private void seedWave(RoutingFakes.InMemoryWaveDao dao, Map<Long, Integer> extraTimeByTask,
                          LocalDateTime windowEndAt) {
        dao.putWave(new RoutingWaveRow(WAVE_ID, "BC202608110001", 7L, "DELIVERING", WAVE_START, WAVE_START, null));
        for (int i = 0; i < TASK_IDS.length; i++) {
            long taskId = TASK_IDS[i];
            dao.putTask(new RoutingTaskRow(taskId, WAVE_ID, "DELIVERING",
                    30.100000d + (i + 1) * 0.005d, 120.700000d + (i + 1) * 0.005d, "NORMAL",
                    null, windowEndAt, windowEndAt, null, null, null, null,
                    extraTimeByTask.getOrDefault(taskId, 0), null, null, WAVE_START, null, null));
            dao.putStop(new RoutingStopRow(WAVE_ID, taskId, i + 1, i + 1,
                    30.100000d + (i + 1) * 0.005d, 120.700000d + (i + 1) * 0.005d,
                    1000, 240, 180, null, null, null, null, false));
        }
    }

    private Map<Long, LocalDateTime> etaByTask(EtaEngine.WaveEtaResult result) {
        Map<Long, LocalDateTime> map = new java.util.LinkedHashMap<>();
        List<EtaEngine.TaskEta> tasks = new ArrayList<>(result.tasks());
        for (EtaEngine.TaskEta task : tasks) {
            map.put(task.taskId(), task.eta().etaAt());
        }
        return map;
    }

    private static long secondsBetween(LocalDateTime before, LocalDateTime after) {
        return Duration.between(before, after).getSeconds();
    }

    private static RoutingTaskRow withWindowStart(RoutingTaskRow task, LocalDateTime windowStartAt) {
        return new RoutingTaskRow(
                task.taskId(), task.waveId(), task.status(), task.lat(), task.lng(), task.coldChainLevel(),
                windowStartAt, task.windowEndAt(), task.promisedAt(), task.groupKey(), task.areaLabel(),
                task.buildingLabel(), task.floorNo(), task.extraTimeSeconds(), task.extraTimeReason(),
                task.handoffSeconds(), task.pickedReadyAt(), task.arrivedAt(), task.deliveredAt());
    }
}
