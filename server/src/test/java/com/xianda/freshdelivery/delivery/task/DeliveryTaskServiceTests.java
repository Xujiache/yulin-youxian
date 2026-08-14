package com.xianda.freshdelivery.delivery.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.domain.DeliveryWave;
import com.xianda.freshdelivery.delivery.dto.BatchPickReadyRequest;
import com.xianda.freshdelivery.delivery.dto.DeliverRequest;
import com.xianda.freshdelivery.delivery.dto.PickReadyRequest;
import com.xianda.freshdelivery.delivery.dto.PickReadyResponse;
import com.xianda.freshdelivery.delivery.dto.PickupRequest;
import com.xianda.freshdelivery.delivery.dto.RejectRequest;
import com.xianda.freshdelivery.delivery.dto.ReturnRequest;
import com.xianda.freshdelivery.delivery.dto.TaskActionRequest;
import com.xianda.freshdelivery.delivery.dto.TaskCardDto;
import com.xianda.freshdelivery.delivery.dto.TasksByOrdersDto;
import com.xianda.freshdelivery.delivery.dto.TransferRequest;
import com.xianda.freshdelivery.delivery.dto.WaveCreateRequest;
import com.xianda.freshdelivery.dto.BatchOrderActionResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryTaskServiceTests {
    private static final long RIDER_ID = 1L;
    private static final long OTHER_RIDER_ID = 2L;

    private DeliveryTaskTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = new DeliveryTaskTestHarness();
        harness.insertRider(RIDER_ID, "张三");
        harness.insertRider(OTHER_RIDER_ID, "李四");
        harness.setConfig(DeliveryConfigPort.REQUIRE_PHOTO, "false");
        harness.setConfig(DeliveryConfigPort.REQUIRE_VERIFY_CODE, "false");
        harness.setConfig(DeliveryConfigPort.HOLD_WINDOW_SECONDS, "120");
    }

    @Test
    void pickReadyCreatesPendingTaskWithGeneratedNoAndHoldWindow() {
        harness.orderBridgePort.putOrder(1001L, "XD20260811001", "备货中");

        PickReadyResponse response = harness.taskService.pickReady(
                1001L,
                new PickReadyRequest(8, 6.5, 2, "FROZEN", null, true, "西红柿实称 1.05 kg"),
                TaskOperator.admin("调度员A")
        );

        assertEquals("PENDING", response.status());
        // 单号里的日期由服务端按门店时区(Asia/Shanghai)生成,断言必须用同一个时区。
        // 用 LocalDate.now() 时,构建机时区只要快于东八区,跨日那一小时就会误报失败。
        String prefix = "PS" + TaskTimes.today().format(TaskTimes.DAY_KEY);
        assertTrue(response.taskNo().startsWith(prefix), "任务号应为 PS+yyyyMMdd+6位：" + response.taskNo());
        assertEquals(prefix.length() + 6, response.taskNo().length());
        assertEquals("000001", response.taskNo().substring(prefix.length()));
        assertNotNull(response.holdUntilAt());

        DeliveryTask task = harness.taskService.requireTask(response.taskId());
        assertEquals("FROZEN", task.coldChainLevel());
        assertEquals(8, task.itemCount());
        assertEquals("138****5678", task.receiverPhoneMasked());
        assertEquals(120L, java.time.Duration.between(task.pickedReadyAt(), task.holdUntilAt()).getSeconds());
        assertEquals(1, harness.eventCount(task.id()));
    }

    @Test
    void pickReadySequenceIncrementsWithinSameDay() {
        harness.orderBridgePort.putOrder(1001L, "XD20260811001", "备货中");
        harness.orderBridgePort.putOrder(1002L, "XD20260811002", "备货中");

        String first = harness.taskService.pickReady(1001L, null, TaskOperator.admin("A")).taskNo();
        String second = harness.taskService.pickReady(1002L, null, TaskOperator.admin("A")).taskNo();

        assertTrue(first.endsWith("000001"));
        assertTrue(second.endsWith("000002"));
    }

    @Test
    void pickReadyRejectsOrderNotInPreparingWith1013() {
        harness.orderBridgePort.putOrder(1001L, "XD20260811001", "已支付/待接单");

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.pickReady(1001L, null, TaskOperator.admin("A"))
        );
        assertEquals(DeliveryErrorCode.ORDER_NOT_DISPATCHABLE, exception.code());
    }

    @Test
    void pickReadyRejectsMissingOrderWith1013() {
        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.pickReady(9999L, null, TaskOperator.admin("A"))
        );
        assertEquals(DeliveryErrorCode.ORDER_NOT_DISPATCHABLE, exception.code());
    }

    @Test
    void pickReadyRejectsDuplicateActiveTaskWith1014() {
        harness.orderBridgePort.putOrder(1001L, "XD20260811001", "备货中");
        harness.taskService.pickReady(1001L, null, TaskOperator.admin("A"));

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.pickReady(1001L, null, TaskOperator.admin("A"))
        );
        assertEquals(DeliveryErrorCode.TASK_ALREADY_EXISTS, exception.code());
    }

    @Test
    void pickReadyReusesTerminalTaskRow() {
        harness.orderBridgePort.putOrder(1001L, "XD20260811001", "备货中");
        long taskId = harness.taskService.pickReady(1001L, null, TaskOperator.admin("A")).taskId();
        harness.taskService.cancelTask(taskId, "客户改约", TaskOperator.admin("A"));
        harness.orderBridgePort.setStatus(1001L, "备货中");

        PickReadyResponse again = harness.taskService.pickReady(1001L, null, TaskOperator.admin("A"));

        assertEquals(taskId, again.taskId());
        assertEquals("PENDING", again.status());
        assertEquals(1, harness.taskCount().get());
        assertNull(harness.taskService.requireTask(taskId).closedAt());
    }

    @Test
    void batchPickReadyReportsRequestedSuccessSkippedAndErrors() {
        harness.orderBridgePort.putOrder(1001L, "XD001", "备货中");
        harness.orderBridgePort.putOrder(1002L, "XD002", "配送中");

        BatchOrderActionResult result = harness.taskService.batchPickReady(
                new BatchPickReadyRequest(List.of(1001L, 1002L, 1003L), true),
                TaskOperator.admin("A")
        );

        assertEquals(3, result.requested());
        assertEquals(1, result.success());
        assertEquals(2, result.skipped());
        assertEquals(List.of(1001L), result.processedOrderIds());
        assertEquals(2, result.errors().size());
    }

    @Test
    void riderCannotOperateTaskOwnedByAnotherRiderWith1012() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "MANUAL", 0.8, null);

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.accept(OTHER_RIDER_ID, taskId, new TaskActionRequest("evt-x", null, null))
        );
        assertEquals(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, exception.code());
        assertEquals("ASSIGNED", harness.taskStatus(taskId));
    }

    @Test
    void sameClientEventIdThreeTimesProducesOneEventAndSameResult() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "MANUAL", 0.8, null);
        int eventsBefore = harness.eventCount(taskId);

        TaskActionRequest request = new TaskActionRequest("evt-accept-1", "2026-08-11T15:40:12", null);
        TaskCardDto first = harness.taskService.accept(RIDER_ID, taskId, request);
        TaskCardDto second = harness.taskService.accept(RIDER_ID, taskId, request);
        TaskCardDto third = harness.taskService.accept(RIDER_ID, taskId, request);

        assertEquals("ACCEPTED", first.status());
        assertEquals(first.status(), second.status());
        assertEquals(first.status(), third.status());
        assertEquals(first.taskId(), third.taskId());
        assertEquals(1, harness.eventCountByClientEventId("evt-accept-1"));
        assertEquals(eventsBefore + 1, harness.eventCount(taskId));
    }

    @Test
    void replayIsRecognisedAfterCacheEvictionThroughEventTable() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "MANUAL", 0.8, null);
        TaskActionRequest request = new TaskActionRequest("evt-accept-2", null, null);
        harness.taskService.accept(RIDER_ID, taskId, request);
        harness.idempotencyGuard.evict("evt-accept-2");

        TaskCardDto replayed = harness.taskService.accept(RIDER_ID, taskId, request);

        assertEquals("ACCEPTED", replayed.status());
        assertEquals(1, harness.eventCountByClientEventId("evt-accept-2"));
    }

    @Test
    void rejectReturnsTaskToPendingAndIncreasesReassignCount() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.5, null);

        TaskCardDto card = harness.taskService.reject(RIDER_ID, taskId, new RejectRequest("evt-r", null, null, "太远了"));

        assertEquals("PENDING", card.status());
        DeliveryTask task = harness.taskService.requireTask(taskId);
        assertNull(task.riderId());
        assertEquals(1, task.reassignCount());
    }

    @Test
    void rejectReplayReturnsSameResultEvenAfterRiderCleared() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.5, null);
        RejectRequest request = new RejectRequest("evt-reject-1", null, null, "太远了");
        harness.taskService.reject(RIDER_ID, taskId, request);
        harness.idempotencyGuard.evict("evt-reject-1");

        TaskCardDto replayed = harness.taskService.reject(RIDER_ID, taskId, request);

        assertEquals("PENDING", replayed.status());
        assertEquals(1, harness.eventCountByClientEventId("evt-reject-1"));
        assertEquals(1, harness.taskService.requireTask(taskId).reassignCount());
    }

    @Test
    void fullHappyPathSyncsOrderStatusThroughBridge() {
        long taskId = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(taskId)), TaskOperator.admin("A")
        );
        harness.waveDao.updateRider(waveId, RIDER_ID, TaskTimes.now());
        harness.taskService.assignTask(taskId, RIDER_ID, waveId, "AUTO", 0.9, "{\"score\":0.9}");
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-a", null, null));

        harness.taskService.pickupWave(RIDER_ID, waveId, new PickupRequest("evt-p", null, null, List.of(), 2));
        assertEquals("配送中", harness.orderBridgePort.status(1001L));

        harness.taskService.depart(RIDER_ID, taskId, new TaskActionRequest("evt-d", null, null));
        assertEquals("DELIVERING", harness.taskStatus(taskId));

        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-ar", null, null));
        assertEquals("ARRIVED", harness.taskStatus(taskId));

        TaskCardDto delivered = harness.taskService.deliver(
                RIDER_ID, taskId, new DeliverRequest("evt-dl", null, null, null, List.of(), "DOOR")
        );

        assertEquals("DELIVERED", delivered.status());
        assertEquals("已完成", harness.orderBridgePort.status(1001L));
        assertNotNull(harness.taskService.requireTask(taskId).deliveredAt());
        assertTrue(harness.waveEtaPort.waveIds().contains(waveId));
        // 送完不等于收工：骑手还在最后一个顾客门口，调度台不能据此认为他空出来了
        assertEquals("RETURNING", harness.waveDao.findById(waveId).orElseThrow().status());

        harness.taskService.returnToStore(RIDER_ID, waveId, new TaskActionRequest("evt-rt", null, null));
        DeliveryWave finished = harness.waveDao.findById(waveId).orElseThrow();
        assertEquals("COMPLETED", finished.status());
        assertNotNull(finished.returnedAt(), "回店时间要落库，调度台据此发下一个时段");
    }

    @Test
    void 还有单没送完时不能确认回店() {
        long taskId = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(taskId)), TaskOperator.admin("A")
        );
        harness.waveDao.updateRider(waveId, RIDER_ID, TaskTimes.now());
        harness.taskService.assignTask(taskId, RIDER_ID, waveId, "MANUAL", null, null);

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.returnToStore(
                        RIDER_ID, waveId, new TaskActionRequest("evt-rt", null, null))
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
    }

    @Test
    void 空波次不能被骑手一键标成完成() {
        // 空波次里没有「未结束的单」，原来这一条就直接把它置成 COMPLETED 了，连留痕都没有。
        long taskId = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(taskId)), TaskOperator.admin("A")
        );
        harness.waveDao.updateRider(waveId, RIDER_ID, TaskTimes.now());
        harness.taskDao.updateWave(taskId, null, TaskTimes.now());

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.returnToStore(
                        RIDER_ID, waveId, new TaskActionRequest("evt-rt-empty", null, null))
        );

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertEquals("ASSIGNED", harness.waveDao.findById(waveId).orElseThrow().status());
        assertNull(harness.waveDao.findById(waveId).orElseThrow().returnedAt());
    }

    @Test
    void 确认回店会按上报端标识落一条留痕() {
        long taskId = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(taskId)), TaskOperator.admin("A")
        );
        harness.taskService.assignTask(taskId, RIDER_ID, waveId, "MANUAL", null, null);
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-a-rt", null, null));
        harness.taskService.pickupWave(RIDER_ID, waveId, new PickupRequest("evt-p-rt", null, null, List.of(), 1));
        harness.taskService.depart(RIDER_ID, taskId, new TaskActionRequest("evt-d-rt", null, null));
        harness.taskService.deliver(RIDER_ID, taskId,
                new DeliverRequest("evt-dl-rt", null, null, null, List.of(), "DOOR"));

        harness.taskService.returnToStore(
                RIDER_ID, waveId, new TaskActionRequest("evt-rt-audit", "2026-08-11T16:05:00", null));

        assertEquals("COMPLETED", harness.waveDao.findById(waveId).orElseThrow().status());
        assertEquals(1, harness.eventCountByClientEventId("evt-rt-audit"),
                "回店时间是能不能发下一个时段的唯一依据，必须留痕");
    }

    @Test
    void 最后一单挂异常时波次转到待回店并且骑手能确认回店() {
        // 一单送达、一单顾客联系不上。骑手这边已经没活，不该把这一波卡死。
        long delivered = pendingTask(1001L, "XD001");
        long stuck = pendingTask(1002L, "XD002");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(delivered, stuck)), TaskOperator.admin("A")
        );
        harness.waveDao.updateRider(waveId, RIDER_ID, TaskTimes.now());
        harness.taskService.assignTask(delivered, RIDER_ID, waveId, "MANUAL", null, null);
        harness.taskService.assignTask(stuck, RIDER_ID, waveId, "MANUAL", null, null);
        harness.taskService.acceptWave(RIDER_ID, waveId, new TaskActionRequest("evt-aw-x", null, null));
        harness.taskService.pickupWave(RIDER_ID, waveId, new PickupRequest("evt-p-x", null, null, List.of(), 2));
        harness.taskService.depart(RIDER_ID, delivered, new TaskActionRequest("evt-d1-x", null, null));
        harness.taskService.deliver(RIDER_ID, delivered,
                new DeliverRequest("evt-dl1-x", null, null, null, List.of(), "DOOR"));
        harness.taskService.depart(RIDER_ID, stuck, new TaskActionRequest("evt-d2-x", null, null));
        harness.taskService.arrive(RIDER_ID, stuck, new TaskActionRequest("evt-ar2-x", null, null));

        harness.taskService.enterException(stuck, 77L);

        assertEquals("EXCEPTION", harness.taskStatus(stuck));
        assertEquals("RETURNING", harness.waveDao.findById(waveId).orElseThrow().status());

        harness.taskService.returnToStore(RIDER_ID, waveId, new TaskActionRequest("evt-rt-x", null, null));
        assertEquals("COMPLETED", harness.waveDao.findById(waveId).orElseThrow().status());
        assertEquals("EXCEPTION", harness.taskStatus(stuck), "回店不该把异常单自行销掉，调度还要处理");
    }

    @Test
    void 还有待送的单时异常不能让波次提前转待回店() {
        long delivering = pendingTask(1001L, "XD001");
        long stuck = pendingTask(1002L, "XD002");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(delivering, stuck)), TaskOperator.admin("A")
        );
        harness.waveDao.updateRider(waveId, RIDER_ID, TaskTimes.now());
        harness.taskService.assignTask(delivering, RIDER_ID, waveId, "MANUAL", null, null);
        harness.taskService.assignTask(stuck, RIDER_ID, waveId, "MANUAL", null, null);
        harness.taskService.acceptWave(RIDER_ID, waveId, new TaskActionRequest("evt-aw-y", null, null));
        harness.taskService.pickupWave(RIDER_ID, waveId, new PickupRequest("evt-p-y", null, null, List.of(), 2));
        harness.taskService.depart(RIDER_ID, delivering, new TaskActionRequest("evt-d1-y", null, null));
        harness.taskService.depart(RIDER_ID, stuck, new TaskActionRequest("evt-d2-y", null, null));
        harness.taskService.arrive(RIDER_ID, stuck, new TaskActionRequest("evt-ar2-y", null, null));
        harness.taskService.enterException(stuck, 78L);

        assertEquals("EXCEPTION", harness.taskStatus(stuck));
        assertEquals("DELIVERING", harness.taskStatus(delivering));
        assertEquals("DELIVERING", harness.waveDao.findById(waveId).orElseThrow().status());
        assertThrows(DeliveryException.class, () -> harness.taskService.returnToStore(
                RIDER_ID, waveId, new TaskActionRequest("evt-rt-y", null, null)));
    }

    @Test
    void 整波次接单把全部已派单转成已接单() {
        long first = pendingTask(1001L, "XD001");
        long second = pendingTask(1002L, "XD002");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(first, second)), TaskOperator.admin("A")
        );
        harness.waveDao.updateRider(waveId, RIDER_ID, TaskTimes.now());
        harness.taskService.assignTask(first, RIDER_ID, waveId, "MANUAL", null, null);
        harness.taskService.assignTask(second, RIDER_ID, waveId, "MANUAL", null, null);

        harness.taskService.acceptWave(RIDER_ID, waveId, new TaskActionRequest("evt-aw", null, null));

        assertEquals("ACCEPTED", harness.taskStatus(first));
        assertEquals("ACCEPTED", harness.taskStatus(second));
    }

    @Test
    void 整波次接单可以重复调用() {
        long taskId = pendingTask(1001L, "XD001");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(taskId)), TaskOperator.admin("A")
        );
        harness.waveDao.updateRider(waveId, RIDER_ID, TaskTimes.now());
        harness.taskService.assignTask(taskId, RIDER_ID, waveId, "MANUAL", null, null);

        // 网络重试和重复点击都会走到这里，第二次不能报错
        harness.taskService.acceptWave(RIDER_ID, waveId, new TaskActionRequest("evt-aw", null, null));
        harness.taskService.acceptWave(RIDER_ID, waveId, new TaskActionRequest("evt-aw", null, null));

        assertEquals("ACCEPTED", harness.taskStatus(taskId));
    }

    @Test
    void returnFromDeliveringPassesThroughExceptionAndKeepsOrderDelivering() {
        long taskId = deliveringTask(1001L, "XD001");
        assertEquals("配送中", harness.orderBridgePort.status(1001L));
        harness.orderBridgePort.calls().clear();

        TaskCardDto card = harness.taskService.markReturned(
                RIDER_ID, taskId, new ReturnRequest("evt-ret", null, null, "顾客拒收", List.of())
        );

        assertEquals("RETURNED", card.status());
        assertEquals("配送中", harness.orderBridgePort.status(1001L));
        assertTrue(harness.orderBridgePort.calls().isEmpty(), "RETURNED 不应触发任何订单侧调用");
        List<String> statuses = harness.eventDao.findByTaskId(taskId).stream()
                .map(event -> event.fromStatus() + "->" + event.toStatus())
                .toList();
        assertTrue(statuses.contains("DELIVERING->EXCEPTION"), statuses.toString());
        assertTrue(statuses.contains("EXCEPTION->RETURNED"), statuses.toString());
    }

    @Test
    void transferFromPickedUpIsRejectedByFrozenStateMachine() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.9, null);
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-a", null, null));
        harness.taskDao.updateStatus(taskId, "ACCEPTED", "PICKED_UP", TaskTimes.now());

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.transfer(RIDER_ID, taskId, new TransferRequest("evt-t", null, null, "车坏了"))
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
    }

    @Test
    void transferFromAcceptedReturnsTaskToPending() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.9, null);
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-a", null, null));

        TaskCardDto card = harness.taskService.transfer(
                RIDER_ID, taskId, new TransferRequest("evt-t", null, null, "车坏了")
        );

        assertEquals("PENDING", card.status());
        assertNull(harness.taskService.requireTask(taskId).riderId());
    }

    @Test
    void illegalTransitionThrows1011() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.9, null);

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-x", null, null))
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
    }

    @Test
    void deliverEnforcesVerifyCodeAndPhotoConfiguration() {
        harness.setConfig(DeliveryConfigPort.REQUIRE_VERIFY_CODE, "true");
        harness.setConfig(DeliveryConfigPort.REQUIRE_PHOTO, "true");
        long taskId = deliveringTask(1001L, "XD001");

        DeliveryException missingCode = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.deliver(
                        RIDER_ID, taskId, new DeliverRequest("evt-1", null, null, null, List.of(9L), "DOOR")
                )
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, missingCode.code());

        String verifyCode = harness.taskService.issueVerificationCode(taskId, TaskOperator.admin("A")).code();
        DeliveryException missingPhoto = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.deliver(
                        RIDER_ID, taskId, new DeliverRequest("evt-2", null, null, verifyCode, List.of(), "DOOR")
                )
        );
        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, missingPhoto.code());
        assertEquals("DELIVERING", harness.taskStatus(taskId));

        long evidenceId = harness.insertEvidence(taskId, RIDER_ID, "DELIVERED");
        TaskCardDto card = harness.taskService.deliver(
                RIDER_ID, taskId,
                new DeliverRequest("evt-3", null, null, verifyCode, List.of(evidenceId), "DOOR")
        );
        assertEquals("DELIVERED", card.status());
    }

    @Test
    void optionalVerifyCodeStillRejectsArbitraryClientString() {
        long taskId = deliveringTask(1001L, "XD001");

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.deliver(
                        RIDER_ID, taskId,
                        new DeliverRequest("evt-fake-code", null, null, "123456", List.of(), "DOOR")
                )
        );

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertEquals("DELIVERING", harness.taskStatus(taskId));
    }

    @Test
    void deliverRejectsMissingCrossTaskCrossRiderAndWrongTypeEvidence() {
        long taskId = deliveringTask(1001L, "XD001");
        long otherTaskId = deliveringTask(1002L, "XD002");
        long otherTaskEvidence = harness.insertEvidence(otherTaskId, RIDER_ID, "DELIVERED");
        long otherRiderEvidence = harness.insertEvidence(taskId, OTHER_RIDER_ID, "DELIVERED");
        long wrongTypeEvidence = harness.insertEvidence(taskId, RIDER_ID, "EXCEPTION");

        for (long evidenceId : List.of(999999L, otherTaskEvidence, otherRiderEvidence, wrongTypeEvidence)) {
            DeliveryException exception = assertThrows(
                    DeliveryException.class,
                    () -> harness.taskService.deliver(
                            RIDER_ID, taskId,
                            new DeliverRequest("evt-fake-evidence-" + evidenceId, null, null,
                                    null, List.of(evidenceId), "DOOR")
                    )
            );
            assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        }
        assertEquals("DELIVERING", harness.taskStatus(taskId));
    }

    @Test
    void idempotencyScopeIncludesTaskRiderAndAction() {
        long firstTask = pendingTask(1001L, "XD001");
        long secondTask = pendingTask(1002L, "XD002");
        harness.taskService.assignTask(firstTask, RIDER_ID, null, "AUTO", 0.8, null);
        harness.taskService.assignTask(secondTask, RIDER_ID, null, "AUTO", 0.8, null);
        TaskActionRequest sameId = new TaskActionRequest("evt-shared", null, null);

        harness.taskService.accept(RIDER_ID, firstTask, sameId);
        harness.taskService.accept(RIDER_ID, secondTask, sameId);
        TaskCardDto rejected = harness.taskService.reject(
                RIDER_ID, firstTask, new RejectRequest("evt-shared", null, null, "改派"));

        assertEquals("PENDING", rejected.status(), "同 ID 但不同 action 不能误判为 ACCEPT 重放");
        assertEquals("ACCEPTED", harness.taskStatus(secondTask));
        assertEquals(3, harness.eventCountByClientEventId("evt-shared"));
    }

    @Test
    void concurrentSameScopedEventCreatesExactlyOneTransition() throws Exception {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.8, null);
        TaskActionRequest request = new TaskActionRequest("evt-concurrent", null, null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                start.await();
                return harness.taskService.accept(RIDER_ID, taskId, request);
            });
            var second = pool.submit(() -> {
                start.await();
                return harness.taskService.accept(RIDER_ID, taskId, request);
            });
            start.countDown();
            assertEquals("ACCEPTED", first.get().status());
            assertEquals("ACCEPTED", second.get().status());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, harness.eventCountByClientEventId("evt-concurrent"));
    }

    @Test
    void concurrentReassignCannotLeaveTaskAcceptedByOldRider() throws Exception {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.8, null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            var accept = pool.submit(() -> {
                start.await();
                try {
                    harness.taskService.accept(
                            RIDER_ID, taskId, new TaskActionRequest("evt-race-accept", null, null));
                } catch (DeliveryException expectedRaceLoss) {
                    assertEquals(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER, expectedRaceLoss.code());
                }
                return null;
            });
            var reassign = pool.submit(() -> {
                start.await();
                harness.taskService.reassignTask(
                        taskId, OTHER_RIDER_ID, "并发改派", "ADMIN", "调度员");
                return null;
            });
            start.countDown();
            accept.get();
            reassign.get();
        } finally {
            pool.shutdownNow();
        }

        DeliveryTask finalTask = harness.taskService.requireTask(taskId);
        assertEquals(OTHER_RIDER_ID, finalTask.riderId());
        assertEquals("ASSIGNED", finalTask.status());
    }

    @Test
    void replayRepairsBridgeAfterCommittedTerminalTask() {
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-arrive", null, null));
        DeliverRequest request = new DeliverRequest("evt-bridge-crash", null, null, null, List.of(), "SELF");
        harness.orderBridgePort.failCompleteOnce();

        harness.taskService.deliver(RIDER_ID, taskId, request);
        assertEquals("DELIVERED", harness.taskStatus(taskId));
        assertEquals("配送中", harness.orderBridgePort.status(1001L));

        harness.taskService.deliver(RIDER_ID, taskId, request);
        assertEquals("已完成", harness.orderBridgePort.status(1001L));
        assertEquals(1, harness.eventCountByClientEventId("evt-bridge-crash"));
    }

    @Test
    void redispatchStrictlyDetachesOldAttemptHistoryAndEvidence() {
        long taskId = pendingTask(1001L, "XD001");
        harness.insertEvidence(taskId, RIDER_ID, "EXCEPTION");
        harness.taskService.cancelTask(taskId, "客户改约", TaskOperator.admin("A"));
        harness.orderBridgePort.setStatus(1001L, "备货中");

        PickReadyResponse redispatched = harness.taskService.pickReady(1001L, null, TaskOperator.admin("A"));

        assertEquals(taskId, redispatched.taskId());
        assertEquals(1, harness.eventCount(taskId), "新 attempt 不得混入旧事件");
        assertEquals(0, harness.supportDao.countEvidences(taskId, "EXCEPTION"));
    }

    @Test
    void tasksByOrdersReturnsOnlyOrdersWithTasks() {
        long firstTask = pendingTask(1001L, "XD001");
        pendingTask(1002L, "XD002");
        harness.taskService.assignTask(firstTask, RIDER_ID, null, "AUTO", 0.7, null);

        TasksByOrdersDto result = harness.taskService.tasksByOrders(List.of(1001L, 1002L, 1003L));

        assertEquals(2, result.items().size());
        assertTrue(result.items().containsKey(1001L));
        assertTrue(result.items().containsKey(1002L));
        assertFalse(result.items().containsKey(1003L));
        assertEquals("已派单", result.items().get(1001L).statusText());
        assertEquals("张三", result.items().get(1001L).riderName());
        assertEquals("待分配", result.items().get(1002L).statusText());
        assertNull(result.items().get(1002L).riderName());
    }

    @Test
    void tasksByOrdersRejectsMoreThanHundredIds() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.tasksByOrders(ids)
        );
        assertEquals(DeliveryErrorCode.ORDER_NOT_DISPATCHABLE, exception.code());
    }

    @Test
    void assignTaskNotifiesRiderAndRecordsAssignEvent() {
        long taskId = pendingTask(1001L, "XD001");

        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.8123, "{\"reason\":\"顺路度高\"}");

        assertEquals("ASSIGNED", harness.taskStatus(taskId));
        assertTrue(harness.riderNotifyPort.messages().stream().anyMatch(text -> text.startsWith("1:TASK_ASSIGNED")));
        assertTrue(harness.eventDao.findByTaskId(taskId).stream()
                .anyMatch(event -> DeliveryTaskEventRecorder.TYPE_ASSIGN.equals(event.eventType())
                        && event.detailJson() != null
                        && event.detailJson().contains("顺路度高")));
    }

    @Test
    void reassignTaskMovesTaskToNewRiderAndKeepsAudit() {
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.8, null);

        harness.taskService.reassignTask(taskId, OTHER_RIDER_ID, "原骑手车辆故障", "ADMIN", "调度员A");

        DeliveryTask task = harness.taskService.requireTask(taskId);
        assertEquals("ASSIGNED", task.status());
        assertEquals(OTHER_RIDER_ID, task.riderId());
        assertEquals(1, task.reassignCount());
        assertTrue(harness.eventDao.findByTaskId(taskId).stream()
                .anyMatch(event -> DeliveryTaskEventRecorder.TYPE_REASSIGN.equals(event.eventType())));
    }

    @Test
    void exceptionEnterAndResolveFollowFrozenStateMachine() {
        long taskId = deliveringTask(1001L, "XD001");

        harness.taskService.enterException(taskId, 77L);
        assertEquals("EXCEPTION", harness.taskStatus(taskId));
        assertEquals(77L, harness.taskService.requireTask(taskId).currentExceptionId());

        harness.taskService.resolveException(taskId, "CONTINUE", "顾客已联系上", "调度员A");
        assertEquals("DELIVERING", harness.taskStatus(taskId));
        assertNull(harness.taskService.requireTask(taskId).currentExceptionId());
    }

    @Test
    void continueRestoresTheStatusTheTaskHadBeforeTheException() {
        // 到达后才上报的异常，解除时要回到「已到达」。
        // 一律推成「配送中」等于把已经到达的事实抹掉，骑手还得再滑一次到达。
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-arrive-x", null, null));
        assertEquals("ARRIVED", harness.taskStatus(taskId));

        harness.taskService.enterException(taskId, 88L);
        harness.taskService.resolveException(taskId, "CONTINUE", "顾客已联系上", "调度员A");

        assertEquals("ARRIVED", harness.taskStatus(taskId));
    }

    @Test
    void continueRestoresAcceptedWhenExceptionWasRaisedBeforePickup() {
        // 接单后、取货前上报的异常，解除后要回到「已接单」，
        // 不能跳过取货直接变成配送中，否则取货时间和交接耗时全是空的。
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.9, null);
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-acc-x", null, null));
        assertEquals("ACCEPTED", harness.taskStatus(taskId));

        harness.taskService.enterException(taskId, 89L);
        harness.taskService.resolveException(taskId, "CONTINUE", "货已补齐", "调度员A");

        assertEquals("ACCEPTED", harness.taskStatus(taskId));
    }

    @Test
    void departRefusesATaskThatHasNotBeenPickedUp() {
        // 骑手对着还没取的货点「发车」，整波取货就被跳过了：picked_up_at 为空，
        // 顾客整程看不到骑手位置，波次也不会 markStarted。
        long taskId = pendingTask(1001L, "XD001");
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.9, null);
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-acc-d", null, null));

        DeliveryException exception = assertThrows(
                DeliveryException.class,
                () -> harness.taskService.depart(RIDER_ID, taskId, new TaskActionRequest("evt-dep-d", null, null))
        );

        assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        assertEquals("ACCEPTED", harness.taskStatus(taskId));
        assertNull(harness.taskService.requireTask(taskId).pickedUpAt());
        assertNull(harness.taskService.requireTask(taskId).departedAt());
    }

    @Test
    void riderCannotPushATaskOutOfExceptionWhileTheExceptionIsStillOpen() {
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.enterException(taskId, 91L);

        for (Runnable riderAction : List.<Runnable>of(
                () -> harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-x-acc", null, null)),
                () -> harness.taskService.pickup(RIDER_ID, taskId, new TaskActionRequest("evt-x-pick", null, null)),
                () -> harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-x-arr", null, null)),
                () -> harness.taskService.depart(RIDER_ID, taskId, new TaskActionRequest("evt-x-dep", null, null))
        )) {
            DeliveryException exception = assertThrows(DeliveryException.class, riderAction::run);
            assertEquals(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, exception.code());
        }

        assertEquals("EXCEPTION", harness.taskStatus(taskId));
        assertEquals(91L, harness.taskService.requireTask(taskId).currentExceptionId(),
                "异常单没被调度员处理，current_exception_id 不能被骑手顺手清掉");
    }

    @Test
    void riderCanStillReturnOrCloseATaskThatIsStuckInException() {
        // 货退回是异常的合法出口，不能被上面那道闸门一起挡掉。
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.enterException(taskId, 92L);

        TaskCardDto card = harness.taskService.markReturned(
                RIDER_ID, taskId, new ReturnRequest("evt-x-ret", null, null, "顾客拒收", List.of()));

        assertEquals("RETURNED", card.status());
    }

    @Test
    void exceptionRecoveryKeepsTheOriginalFulfilmentTimestamps() {
        // 「挂异常 → 调度员点继续」会重走一遍中间态。真实的接单/取货/到达时刻被改写成
        // 异常解除时刻之后，交接耗时和取货到送达的时长就都不是现场发生的事了。
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-arr-ts", null, null));
        java.time.LocalDateTime acceptedAt = java.time.LocalDateTime.of(2026, 8, 11, 14, 0, 0);
        java.time.LocalDateTime pickedUpAt = java.time.LocalDateTime.of(2026, 8, 11, 14, 10, 0);
        java.time.LocalDateTime arrivedAt = java.time.LocalDateTime.of(2026, 8, 11, 14, 35, 0);
        harness.jdbcTemplate.update(
                "UPDATE delivery_task SET accepted_at = ?, picked_up_at = ?, arrived_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(acceptedAt),
                java.sql.Timestamp.valueOf(pickedUpAt),
                java.sql.Timestamp.valueOf(arrivedAt),
                taskId);

        harness.taskService.enterException(taskId, 93L);
        harness.taskService.resolveException(taskId, "CONTINUE", "顾客已联系上", "调度员A");

        DeliveryTask task = harness.taskService.requireTask(taskId);
        assertEquals("ARRIVED", task.status());
        assertEquals(acceptedAt, task.acceptedAt());
        assertEquals(pickedUpAt, task.pickedUpAt());
        assertEquals(arrivedAt, task.arrivedAt());
    }

    @Test
    void autoMarkArrivedIsIdempotent() {
        long taskId = deliveringTask(1001L, "XD001");

        harness.taskService.autoMarkArrived(taskId, 30.12, 120.76);
        harness.taskService.autoMarkArrived(taskId, 30.12, 120.76);

        assertEquals("ARRIVED", harness.taskStatus(taskId));
        assertEquals(1, harness.eventDao.findByTaskId(taskId).stream()
                .filter(event -> DeliveryTaskStatus.ARRIVED.name().equals(event.toStatus()))
                .count());
    }

    @Test
    void riderTaskListGroupsWaveStopsBySequence() {
        long firstTask = pendingTask(1001L, "XD001");
        long secondTask = pendingTask(1002L, "XD002");
        long waveId = harness.waveService.createWave(
                new WaveCreateRequest(RIDER_ID, List.of(firstTask, secondTask)), TaskOperator.admin("A")
        );
        harness.taskService.assignTask(firstTask, RIDER_ID, waveId, "AUTO", 0.9, null);
        harness.taskService.assignTask(secondTask, RIDER_ID, waveId, "AUTO", 0.9, null);
        harness.waveService.resequence(waveId, List.of(secondTask, firstTask), true, TaskOperator.rider(RIDER_ID, "张三"));

        var list = harness.taskService.riderTasks(RIDER_ID, "PENDING_ACCEPT");

        assertEquals(1, list.waves().size());
        assertEquals(List.of(secondTask, firstTask),
                list.waves().get(0).stops().stream().map(TaskCardDto::taskId).toList());
        assertEquals(1, list.waves().get(0).stops().get(0).seqNo());
        var movedStop = harness.waveStopDao.findByWaveAndTask(waveId, secondTask).orElseThrow();
        assertTrue(movedStop.adjustedByRider());
        assertEquals(1, movedStop.seqNo());
        assertEquals(2, movedStop.originalSeqNo(), "骑手调序后必须保留系统规划的原始顺序");
    }

    @Test
    void todayDoneListsTasksBySendingDayNotByCustomerDeliveryDate() {
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-arrive", null, null));
        harness.taskService.deliver(RIDER_ID, taskId, new DeliverRequest("evt-deliver", null, null, null, List.of(), "SELF"));
        harness.jdbcTemplate.update(
                "UPDATE delivery_task SET delivery_date = ? WHERE id = ?",
                java.sql.Date.valueOf(TaskTimes.today().plusDays(1)), taskId);

        var list = harness.taskService.riderTasks(RIDER_ID, "TODAY_DONE");

        assertEquals(1, list.standaloneTasks().size(), "配送日期是明天但今天送完的单，也要出现在「今日已完成」里");
        assertEquals(taskId, list.standaloneTasks().get(0).taskId());
    }

    @Test
    void todayDoneHidesTasksDeliveredOnAnotherDay() {
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-arrive", null, null));
        harness.taskService.deliver(RIDER_ID, taskId, new DeliverRequest("evt-deliver", null, null, null, List.of(), "SELF"));
        harness.jdbcTemplate.update(
                "UPDATE delivery_task SET delivered_at = ?, closed_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(TaskTimes.now().minusDays(1)),
                java.sql.Timestamp.valueOf(TaskTimes.now().minusDays(1)),
                taskId);

        assertTrue(harness.taskService.riderTasks(RIDER_ID, "TODAY_DONE").standaloneTasks().isEmpty());
    }

    @Test
    void deliverRefreshesRiderStatsAndReplayDoesNotDoubleCount() {
        long taskId = deliveringTask(1001L, "XD001");
        harness.taskService.arrive(RIDER_ID, taskId, new TaskActionRequest("evt-arrive", null, null));
        DeliverRequest request = new DeliverRequest("evt-deliver", null, null, null, List.of(), "SELF");

        harness.taskService.deliver(RIDER_ID, taskId, request);
        harness.taskService.deliver(RIDER_ID, taskId, request);

        assertEquals(List.of(RIDER_ID), harness.riderStatsPort.refreshedRiderIds(),
                "重放的送达请求不能再触发一次统计刷新，统计本身也是重算而不是累加");
    }

    private long pendingTask(long orderId, String orderNo) {
        harness.orderBridgePort.putOrder(orderId, orderNo, "备货中");
        return harness.taskService.pickReady(orderId, null, TaskOperator.admin("A")).taskId();
    }

    private long deliveringTask(long orderId, String orderNo) {
        long taskId = pendingTask(orderId, orderNo);
        harness.taskService.assignTask(taskId, RIDER_ID, null, "AUTO", 0.9, null);
        harness.taskService.accept(RIDER_ID, taskId, new TaskActionRequest("evt-accept-" + taskId, null, null));
        harness.taskDao.updateStatus(taskId, "ACCEPTED", "PICKED_UP", TaskTimes.now());
        harness.orderBridgePort.markDelivering(orderId);
        harness.taskService.depart(RIDER_ID, taskId, new TaskActionRequest("evt-depart-" + taskId, null, null));
        return taskId;
    }
}
