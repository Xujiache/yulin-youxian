package com.xianda.freshdelivery.delivery.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderStatusBridgeTests {
    private static final long ORDER_ID = 1001L;

    private DeliveryTaskTestHarness harness;
    private long taskId;

    @BeforeEach
    void setUp() {
        harness = new DeliveryTaskTestHarness();
        harness.orderBridgePort.putOrder(ORDER_ID, "XD20260811001", "备货中");
        taskId = harness.taskService.pickReady(ORDER_ID, null, TaskOperator.admin("A")).taskId();
        harness.orderBridgePort.calls().clear();
    }

    @Test
    void pickedUpMovesOrderToDelivering() {
        harness.orderStatusBridge.syncAfterCommit(task(), DeliveryTaskStatus.PICKED_UP);

        assertEquals(List.of("markDelivering:" + ORDER_ID), harness.orderBridgePort.calls());
        assertEquals("配送中", harness.orderBridgePort.status(ORDER_ID));
    }

    @Test
    void deliveredMovesOrderToCompleted() {
        harness.orderBridgePort.setStatus(ORDER_ID, "配送中");

        harness.orderStatusBridge.syncAfterCommit(task(), DeliveryTaskStatus.DELIVERED);

        assertEquals(List.of("markCompleted:" + ORDER_ID), harness.orderBridgePort.calls());
        assertEquals("已完成", harness.orderBridgePort.status(ORDER_ID));
    }

    @Test
    void cancelledRestoresOrderToPreparing() {
        harness.orderBridgePort.setStatus(ORDER_ID, "配送中");

        harness.orderStatusBridge.syncAfterCommit(task(), DeliveryTaskStatus.CANCELLED);

        assertEquals(List.of("restoreToPreparing:" + ORDER_ID), harness.orderBridgePort.calls());
        assertEquals("备货中", harness.orderBridgePort.status(ORDER_ID));
    }

    @Test
    void returnedLeavesOrderUntouched() {
        harness.orderBridgePort.setStatus(ORDER_ID, "配送中");

        harness.orderStatusBridge.syncAfterCommit(task(), DeliveryTaskStatus.RETURNED);

        assertTrue(harness.orderBridgePort.calls().isEmpty(), "RETURNED 不允许调用订单域");
        assertEquals("配送中", harness.orderBridgePort.status(ORDER_ID));
    }

    @Test
    void intermediateStatusesDoNotTouchOrder() {
        List<DeliveryTaskStatus> untouched = List.of(
                DeliveryTaskStatus.PENDING,
                DeliveryTaskStatus.ASSIGNED,
                DeliveryTaskStatus.ACCEPTED,
                DeliveryTaskStatus.DELIVERING,
                DeliveryTaskStatus.ARRIVED,
                DeliveryTaskStatus.EXCEPTION
        );

        untouched.forEach(status -> harness.orderStatusBridge.syncAfterCommit(task(), status));

        assertTrue(harness.orderBridgePort.calls().isEmpty());
        assertEquals("备货中", harness.orderBridgePort.status(ORDER_ID));
    }

    @Test
    void bridgeFailureIsRecordedAsNoteEventAndDoesNotPropagate() {
        harness.orderBridgePort.setStatus(ORDER_ID, "配送中");
        harness.orderBridgePort.failRestore();
        int eventsBefore = harness.eventCount(taskId);

        harness.orderStatusBridge.syncAfterCommit(task(), DeliveryTaskStatus.CANCELLED);

        assertEquals(eventsBefore + 1, harness.eventCount(taskId));
        assertTrue(harness.eventDao.findByTaskId(taskId).stream()
                .anyMatch(event -> DeliveryTaskEventRecorder.TYPE_NOTE.equals(event.eventType())
                        && event.detailJson() != null
                        && event.detailJson().contains("\"syncResult\":\"FAILED\"")));
        assertEquals("配送中", harness.orderBridgePort.status(ORDER_ID));
    }

    @Test
    void bridgeIgnoresTaskWithoutOrder() {
        DeliveryTask orphan = new DeliveryTask(
                999L, "PS20260811999999", null, "XD999", null, null, "PENDING",
                "王女士", "13800005678", "138****5678", "阳光小区", null, null, null, null, null, null,
                null, null, null, 1, null, "NORMAL", 1, null, null, null,
                LocalDate.now(), null, null, null, null, null, null, null, null,
                0, null, null, null, null, null, null, null, null, null,
                0, 0, null, null, 0, null, null, 0, null, 0, null, 0, 0, null, null, null
        );

        harness.orderStatusBridge.syncAfterCommit(orphan, DeliveryTaskStatus.PICKED_UP);

        assertTrue(harness.orderBridgePort.calls().isEmpty());
    }

    private DeliveryTask task() {
        return harness.taskService.requireTask(taskId);
    }
}
