package com.xianda.freshdelivery.delivery.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 按时段发车。
 *
 * 自营单店的实际流程是店主自己分单，系统不打分不挑人，只负责把这批单原样落到一个波次里。
 * 这里守住三条：整批成功或整批不动、一波车只装同一天同一时段、同时段再发要并进原波次。
 */
class SlotDispatchServiceTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final long RIDER = 7L;

    private DispatchFakes.MapConfigSource config;
    private RecordingDao dao;
    private RecordingAssignmentPort assignmentPort;
    private RecordingRoutingPort routingPort;
    private SlotDispatchService service;

    @BeforeEach
    void setUp() {
        config = DispatchTestSupport.defaultConfig();
        dao = new RecordingDao();
        assignmentPort = new RecordingAssignmentPort();
        routingPort = new RecordingRoutingPort();
        rebuildService();
    }

    private void rebuildService() {
        DispatchSettings settings = DispatchTestSupport.settings(config);
        RiderScoringService scoringService = new RiderScoringService(
                new RouteEstimator(new DispatchFakes.FakeRoutePlanningPort(), settings), settings);
        service = new SlotDispatchService(dao, assignmentPort, routingPort, TaskUnitOfWork.direct(),
                new DispatchEngine(dao, null, null, null, null, null, null, null, null,
                        settings, DispatchTestSupport.fixedClock()),
                scoringService, settings);
    }

    @Test
    void 一个时段的单全部发给一个骑手并建波次() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        dao.addPending(2L, TODAY, "14:00-15:00");

        SlotDispatchService.SlotDispatchResult result = service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L, 2L)));

        assertTrue(result.waveCreated());
        assertEquals("14:00-15:00", result.slotLabel());
        assertEquals(List.of(1L, 2L), result.taskIds());
        assertEquals(2, assignmentPort.assigned.size());
        assertEquals("14:00-15:00", dao.createdSlot);
        assertTrue(routingPort.planned.contains(result.waveId()), "发车必须触发路径规划");
    }

    @Test
    void 同一时段再发一批要并进原来的波次() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        long waveId = service.dispatch(new SlotDispatchService.SlotDispatchCommand(
                RIDER, "14:00-15:00", List.of(1L))).waveId();

        dao.addPending(2L, TODAY, "14:00-15:00");
        SlotDispatchService.SlotDispatchResult second = service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(2L)));

        assertEquals(waveId, second.waveId(), "同一个时段不该给骑手开两趟车");
        assertFalse(second.waveCreated());
    }

    @Test
    void 混时段发车直接拒绝() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        dao.addPending(2L, TODAY, "19:00-20:00");

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, null, List.of(1L, 2L))));

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertTrue(assignmentPort.assigned.isEmpty(), "拒绝时一单都不能动");
    }

    @Test
    void 混配送日发车直接拒绝() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        dao.addPending(2L, TODAY.plusDays(1), "14:00-15:00");

        assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, null, List.of(1L, 2L))));
        assertTrue(assignmentPort.assigned.isEmpty());
    }

    @Test
    void 请求时段与任务实际时段对不上时拒绝() {
        dao.addPending(1L, TODAY, "14:00-15:00");

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "19:00-20:00", List.of(1L))));

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code(),
                "界面筛的和实际发的不是一批，说明数据已经变了");
    }

    @Test
    void 有任务已经被别人派走时整批不动() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        // 2 号不在待发池里，模拟被并发抢走

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L, 2L))));

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertTrue(assignmentPort.assigned.isEmpty(), "半批成功最难收拾，必须整批回退");
    }

    @Test
    void 骑手不存在时拒绝() {
        dao.addPending(1L, TODAY, "14:00-15:00");

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(999L, null, List.of(1L))));

        assertEquals(DeliveryErrorCode.NO_AVAILABLE_RIDER, exception.code());
    }

    @Test
    void 空任务列表拒绝() {
        assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, null, List.of())));
    }

    @Test
    void 疲劳停派期的骑手不能发车而且不给绕过() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        dao.rider = DispatchTestSupport.rider(RIDER)
                .fatiguePausedUntil(DispatchTestSupport.NOW.plusHours(1)).build();

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L), true)));

        assertEquals(DeliveryErrorCode.RIDER_FATIGUE_SUSPENDED, exception.code());
        assertTrue(assignmentPort.assigned.isEmpty(), "疲劳停派是合规红线，确认参数也不能绕过");
    }

    @Test
    void 不在岗的骑手不能发车() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        dao.rider = DispatchTestSupport.rider(RIDER).workStatus("OFF_DUTY").build();

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L))));

        assertEquals(DeliveryErrorCode.RIDER_OFF_DUTY, exception.code());
        assertTrue(assignmentPort.assigned.isEmpty());
    }

    @Test
    void 账号被停用的骑手不能发车() {
        dao.addPending(1L, TODAY, "14:00-15:00");
        dao.rider = DispatchTestSupport.rider(RIDER).accountStatus("SUSPENDED").build();

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L))));

        assertEquals(DeliveryErrorCode.RIDER_SUSPENDED, exception.code());
        assertTrue(assignmentPort.assigned.isEmpty());
    }

    @Test
    void 超过波次单量上限时默认拒绝确认后放行() {
        config.put(DispatchConfigKeys.MAX_TASKS_PER_WAVE, 2);
        rebuildService();
        dao.addPending(1L, TODAY, "14:00-15:00");
        dao.addPending(2L, TODAY, "14:00-15:00");
        dao.addPending(3L, TODAY, "14:00-15:00");

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L, 2L, 3L))));
        assertEquals(DeliveryErrorCode.RIDER_CONCURRENCY_LIMIT, exception.code());
        assertTrue(assignmentPort.assigned.isEmpty());

        SlotDispatchService.SlotDispatchResult confirmed = service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L, 2L, 3L), true));
        assertEquals(3, confirmed.taskIds().size());
    }

    @Test
    void 超过波次载重上限时默认拒绝() {
        config.put(DispatchConfigKeys.MAX_WAVE_WEIGHT_KG, 5);
        rebuildService();
        dao.addPending(1L, TODAY, "14:00-15:00", 4.0d);
        dao.addPending(2L, TODAY, "14:00-15:00", 4.0d);

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L, 2L))));

        assertEquals(DeliveryErrorCode.RIDER_CONCURRENCY_LIMIT, exception.code());
        assertTrue(exception.getMessage().contains("载重"), exception.getMessage());
    }

    @Test
    void 骑手在手单量超过并发上限时默认拒绝() {
        dao.rider = DispatchTestSupport.rider(RIDER).maxConcurrentTask(2).build();
        dao.addActive(90L);
        dao.addActive(91L);
        dao.addPending(1L, TODAY, "14:00-15:00");

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, "14:00-15:00", List.of(1L))));

        assertEquals(DeliveryErrorCode.RIDER_CONCURRENCY_LIMIT, exception.code());
        assertTrue(exception.getMessage().contains("并发上限"), exception.getMessage());
    }

    @Test
    void 一次发车的任务数有上限() {
        List<Long> tooMany = new ArrayList<>();
        for (long taskId = 1; taskId <= SlotDispatchService.MAX_TASKS_PER_DISPATCH + 1; taskId++) {
            tooMany.add(taskId);
        }

        DeliveryException exception = assertThrows(DeliveryException.class, () -> service.dispatch(
                new SlotDispatchService.SlotDispatchCommand(RIDER, null, tooMany)));

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
    }

    private static final class RecordingAssignmentPort implements TaskAssignmentPort {
        private final List<Long> assigned = new ArrayList<>();

        @Override
        public void assignTask(long taskId, long riderId, Long waveId, String dispatchMode,
                               Double dispatchScore, String detailJson) {
            assigned.add(taskId);
        }

        @Override
        public void reassignTask(long taskId, long toRiderId, String reason,
                                 String operatorType, String operatorName) {
        }
    }

    private static final class RecordingRoutingPort implements WaveRoutingPort {
        private final List<Long> planned = new ArrayList<>();

        @Override
        public void planWave(long waveId, boolean newTaskJoinedExistingWave) {
            planned.add(waveId);
        }

        @Override
        public void replanAfterReassign(long waveId) {
        }

        @Override
        public void recomputeWaveEta(long waveId) {
        }
    }

    private static final class RecordingDao implements DispatchDao {
        private final Map<Long, DispatchTaskRow> pending = new LinkedHashMap<>();
        private final Map<Long, WaveState> waves = new LinkedHashMap<>();
        private final List<DispatchTaskRow> activeTasks = new ArrayList<>();
        private long nextWaveId = 100L;
        private String createdSlot;
        private RiderCandidateRow rider = DispatchTestSupport.rider(RIDER).build();

        private void addPending(long taskId, LocalDate date, String slot) {
            addPending(taskId, date, slot, 1.0d);
        }

        private void addActive(long taskId) {
            activeTasks.add(DispatchTestSupport.task(taskId)
                    .status("ACCEPTED").rider(RIDER).wave(1L).build());
        }

        private void addPending(long taskId, LocalDate date, String slot, double weightKg) {
            pending.put(taskId, new DispatchTaskRow(taskId, "XD" + taskId, null, null, "PENDING",
                    "测试地址", 38.48d, 106.23d, "小区", "1号楼", "key", 1, "101", 1, weightKg, "NORMAL",
                    date, slot, date.atTime(14, 0), date.atTime(15, 0), date.atTime(15, 0),
                    null, 0, null, null, 0, 0, 180));
        }

        @Override
        public List<DispatchTaskRow> findPendingTasks() {
            return List.copyOf(pending.values());
        }

        @Override
        public List<DispatchTaskRow> findTasksByIds(List<Long> taskIds) {
            return taskIds.stream().map(pending::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public Optional<DispatchTaskRow> findTask(long taskId) {
            return Optional.ofNullable(pending.get(taskId));
        }

        @Override
        public List<DispatchTaskRow> lockPendingTasks(List<Long> taskIds) {
            return findTasksByIds(taskIds);
        }

        @Override
        public boolean lockRiders(List<Long> riderIds) {
            return riderIds.stream().allMatch(id -> id == RIDER);
        }

        @Override
        public List<DispatchTaskRow> findActiveTasksByRider(long riderId) {
            return riderId == RIDER ? List.copyOf(activeTasks) : List.of();
        }

        @Override
        public List<DispatchTaskRow> findReassignCandidates() {
            return List.of();
        }

        @Override
        public List<RiderCandidateRow> findRiderPool() {
            return List.of();
        }

        @Override
        public Optional<RiderCandidateRow> findRider(long riderId) {
            return riderId == RIDER ? Optional.of(rider) : Optional.empty();
        }

        @Override
        public Optional<DispatchWaveRow> findAppendableWave(long riderId) {
            return Optional.empty();
        }

        @Override
        public Optional<DispatchWaveRow> findAppendableSlotWave(long riderId, LocalDate deliveryDate,
                                                                String slotLabel) {
            return waves.values().stream()
                    .filter(wave -> wave.riderId == riderId
                            && wave.date.equals(deliveryDate)
                            && java.util.Objects.equals(wave.slot, slotLabel))
                    .findFirst()
                    .map(wave -> new DispatchWaveRow(wave.id, "BC" + wave.id, riderId, "ASSIGNED",
                            wave.date, 0));
        }

        @Override
        public long createWave(Long riderId, LocalDate deliveryDate, LocalDateTime now) {
            return createSlotWave(riderId, deliveryDate, null, now);
        }

        @Override
        public long createSlotWave(Long riderId, LocalDate deliveryDate, String slotLabel, LocalDateTime now) {
            long id = nextWaveId++;
            createdSlot = slotLabel;
            waves.put(id, new WaveState(id, riderId == null ? 0L : riderId, deliveryDate, slotLabel));
            return id;
        }

        @Override
        public void refreshWaveAggregates(long waveId, LocalDateTime now) {
        }

        @Override
        public int countPendingTasks() {
            return pending.size();
        }

        @Override
        public void insertMessage(Long riderId, String messageNo, String type, String title,
                                  String body, boolean requireAck, String payloadJson, String channel) {
        }

        private record WaveState(long id, long riderId, LocalDate date, String slot) {}
    }
}
