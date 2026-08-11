package com.xianda.freshdelivery.delivery.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.dto.TaskActionRequest;
import com.xianda.freshdelivery.delivery.dto.WaveCreateRequest;
import com.xianda.freshdelivery.delivery.dto.WaveDetailDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryWaveServiceTests {
    private static final long RIDER_ID = 1L;

    private DeliveryTaskTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new DeliveryTaskTestHarness();
        harness.insertRider(RIDER_ID, "张三");
        harness.setConfig(DeliveryConfigPort.REQUIRE_PHOTO, "false");
        harness.setConfig(DeliveryConfigPort.MAX_TASKS_PER_WAVE, "8");
    }

    @Test
    void createWaveGeneratesBcNumberAndStops() {
        long first = pendingTask(1001L, "XD001");
        long second = pendingTask(1002L, "XD002");

        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first, second)), TaskOperator.admin("A")
        );

        WaveDetailDto detail = harness.waveService.waveDetail(waveId);
        // 同 DeliveryTaskServiceTests:波次号的日期也按门店时区生成
        String prefix = "BC" + TaskTimes.today().format(TaskTimes.DAY_KEY);
        assertTrue(detail.waveNo().startsWith(prefix), detail.waveNo());
        assertEquals(prefix.length() + 4, detail.waveNo().length());
        assertEquals("0001", detail.waveNo().substring(prefix.length()));
        assertEquals("ASSIGNED", detail.status());
        assertEquals(2, detail.taskCount());
        assertEquals(List.of(1, 2), detail.stops().stream().map(WaveDetailDto.WaveStopDto::seqNo).toList());
        assertEquals(waveId, harness.taskService.requireTask(first).waveId());
    }

    @Test
    void createWaveRejectsMoreTasksThanConfiguredMaximum() {
        harness.setConfig(DeliveryConfigPort.MAX_TASKS_PER_WAVE, "2");
        List<Long> taskIds = List.of(
                pendingTask(1001L, "XD001"), pendingTask(1002L, "XD002"), pendingTask(1003L, "XD003")
        );

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.waveService.createWave(new WaveCreateRequest(RIDER_ID, taskIds), TaskOperator.admin("A"))
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
    }

    @Test
    void appendTasksExtendsWaveAndKeepsSequence() {
        long first = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first)), TaskOperator.admin("A")
        );
        long second = pendingTask(1002L, "XD002");

        harness.waveService.appendTasks(waveId, List.of(second), TaskOperator.admin("A"));

        WaveDetailDto detail = harness.waveService.waveDetail(waveId);
        assertEquals(2, detail.taskCount());
        assertEquals(2, harness.waveStopDao.findByWaveAndTask(waveId, second).orElseThrow().seqNo());
    }

    @Test
    void appendTasksIsIdempotentForAlreadyAttachedTask() {
        long first = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first)), TaskOperator.admin("A")
        );

        harness.waveService.appendTasks(waveId, List.of(first), TaskOperator.admin("A"));

        assertEquals(1, harness.waveStopDao.findByWaveId(waveId).size());
    }

    @Test
    void cancelWaveDetachesTasksWithoutCancellingThem() {
        long first = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first)), TaskOperator.admin("A")
        );

        harness.waveService.cancelWave(waveId, "运力调整", TaskOperator.admin("A"));

        assertEquals("CANCELLED", harness.waveService.requireWave(waveId).status());
        assertTrue(harness.waveStopDao.findByWaveId(waveId).isEmpty());
        assertNull(harness.taskService.requireTask(first).waveId());
        assertEquals("PENDING", harness.taskStatus(first));
    }

    @Test
    void progressCountsClosedAndDeliveredTasks() {
        long first = pendingTask(1001L, "XD001");
        long second = pendingTask(1002L, "XD002");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first, second)), TaskOperator.admin("A")
        );
        harness.taskService.assignTask(first, RIDER_ID, waveId, "AUTO", 0.9, null);
        harness.taskService.accept(RIDER_ID, first, new TaskActionRequest("evt-a1", null, null));
        harness.taskDao.updateStatus(first, "ACCEPTED", "PICKED_UP", TaskTimes.now());
        harness.taskService.depart(RIDER_ID, first, new TaskActionRequest("evt-d1", null, null));
        harness.taskService.deliver(RIDER_ID, first,
                new com.xianda.freshdelivery.delivery.dto.DeliverRequest("evt-x1", null, null, null, List.of(), "DOOR"));

        DeliveryWaveService.WaveProgress progress = harness.waveService.progress(waveId);

        assertEquals(2, progress.taskCount());
        assertEquals(1, progress.closedCount());
        assertEquals(1, progress.deliveredCount());
        assertEquals(0.5d, progress.completionRate());
        assertEquals(1, harness.waveDao.findById(waveId).orElseThrow().completedCount());
    }

    @Test
    void resequenceRejectsTaskOutsideWave() {
        long first = pendingTask(1001L, "XD001");
        long outsider = pendingTask(1002L, "XD002");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first)), TaskOperator.admin("A")
        );

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.waveService.resequence(waveId, List.of(outsider), true, TaskOperator.rider(RIDER_ID, "张三"))
        );
        assertEquals(DeliveryErrorCode.TASK_NOT_FOUND, exception.code());
    }

    @Test
    void riderWithoutWaveOwnershipGets1012() {
        long first = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first)), TaskOperator.admin("A")
        );

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.waveService.ensureRiderOwnsWave(waveId, 99L)
        );
        assertEquals(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, exception.code());
    }

    @Test
    void replayReturnsDownsampledTrack() {
        long first = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first)), TaskOperator.admin("A")
        );
        harness.waveDao.markStarted(waveId, TaskTimes.now().minusMinutes(30));
        for (int i = 0; i < 5; i++) {
            harness.jdbcTemplate.update(
                    "INSERT INTO rider_location (rider_id, wave_id, lat, lng, located_at) VALUES (?,?,?,?,?)",
                    RIDER_ID, waveId, 30.10 + i * 0.001, 120.70 + i * 0.001,
                    java.sql.Timestamp.valueOf(TaskTimes.now().minusMinutes(20 - i))
            );
        }

        var replay = harness.waveService.replay(waveId, 4d);

        assertEquals(5, replay.points().size());
        assertEquals(4d, replay.speed());
        assertEquals(1, replay.stops().size());
    }

    private long pendingTask(long orderId, String orderNo) {
        harness.orderBridgePort.putOrder(orderId, orderNo, "备货中");
        return harness.taskService.pickReady(orderId, null, TaskOperator.admin("A")).taskId();
    }
}
