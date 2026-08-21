package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderStatusBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderStatusBridge.class);

    private final OrderBridgePort orderBridgePort;
    private final DeliveryTaskEventRecorder eventRecorder;
    private final DeliveryTaskEventDao eventDao;
    private final DeliveryTaskDao taskDao;

    @Autowired
    public OrderStatusBridge(
            OrderBridgePort orderBridgePort,
            DeliveryTaskEventRecorder eventRecorder,
            DeliveryTaskEventDao eventDao,
            DeliveryTaskDao taskDao
    ) {
        this.orderBridgePort = orderBridgePort;
        this.eventRecorder = eventRecorder;
        this.eventDao = eventDao;
        this.taskDao = taskDao;
    }

    public OrderStatusBridge(OrderBridgePort orderBridgePort, DeliveryTaskEventRecorder eventRecorder) {
        this.orderBridgePort = orderBridgePort;
        this.eventRecorder = eventRecorder;
        this.eventDao = null;
        this.taskDao = null;
    }

    public void syncAfterCommit(DeliveryTask task, DeliveryTaskStatus taskStatus) {
        if (task == null || task.orderId() == null || taskStatus == null) {
            return;
        }
        if (eventDao != null && taskDao != null && !eventDao.findPendingBridgeEventsForTask(task.id()).isEmpty()) {
            syncPendingForTask(task.id());
            return;
        }
        try {
            apply(task, taskStatus);
        } catch (RuntimeException exception) {
            logFailure(task, taskStatus, exception);
            recordFailure(task, taskStatus, null, exception);
        }
    }

    public synchronized void syncPendingForTask(long taskId) {
        if (eventDao == null || taskDao == null) {
            return;
        }
        DeliveryTask task = taskDao.findById(taskId).orElse(null);
        if (task == null || task.orderId() == null) {
            return;
        }
        for (com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent source
                : eventDao.findPendingBridgeEventsForTask(taskId)) {
            process(task, source);
        }
    }

    public synchronized int retryPending(int limit) {
        if (eventDao == null || taskDao == null) {
            return 0;
        }
        int succeeded = 0;
        List<com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent> pending =
                eventDao.findPendingBridgeEvents(limit);
        for (com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent source : pending) {
            DeliveryTask task = taskDao.findById(source.taskId()).orElse(null);
            if (task != null && task.orderId() != null && process(task, source)) {
                succeeded++;
            }
        }
        return succeeded;
    }

    private boolean process(
            DeliveryTask task,
            com.xianda.freshdelivery.delivery.domain.DeliveryTaskEvent source
    ) {
        DeliveryTaskStatus status;
        try {
            status = DeliveryTaskStatus.valueOf(source.toStatus());
        } catch (RuntimeException exception) {
            return false;
        }
        try {
            apply(task, status);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sourceEventId", source.id());
            detail.put("orderId", task.orderId());
            detail.put("taskStatus", status.name());
            detail.put("syncResult", "SUCCEEDED");
            eventRecorder.record(
                    task,
                    DeliveryTaskEventRecorder.TYPE_ORDER_BRIDGE,
                    status.name(),
                    "SUCCEEDED",
                    TaskOperator.system(),
                    String.valueOf(source.id()),
                    null,
                    null,
                    null,
                    detail
            );
            return true;
        } catch (RuntimeException exception) {
            logFailure(task, status, exception);
            recordFailure(task, status, source.id(), exception);
            return false;
        }
    }

    private void apply(DeliveryTask task, DeliveryTaskStatus taskStatus) {
        switch (taskStatus) {
            case PICKED_UP -> orderBridgePort.markDelivering(task.orderId());
            case DELIVERED -> orderBridgePort.markCompleted(task.orderId());
            case CANCELLED -> orderBridgePort.restoreToPreparing(task.orderId());
            default -> {
            }
        }
    }

    private void logFailure(DeliveryTask task, DeliveryTaskStatus taskStatus, RuntimeException exception) {
        LOGGER.warn(
                "订单状态同步失败：任务 {} 状态 {} 订单 {}：{}",
                task.taskNo(),
                taskStatus,
                task.orderId(),
                exception.getMessage()
        );
    }

    private void recordFailure(
            DeliveryTask task,
            DeliveryTaskStatus taskStatus,
            Long sourceEventId,
            RuntimeException exception
    ) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sourceEventId", sourceEventId);
        detail.put("orderId", task.orderId());
        detail.put("taskStatus", taskStatus.name());
        detail.put("syncResult", "FAILED");
        detail.put("error", exception.getMessage());
        try {
            eventRecorder.record(
                    task,
                    DeliveryTaskEventRecorder.TYPE_NOTE,
                    taskStatus.name(),
                    "FAILED",
                    TaskOperator.system(),
                    // reason 会原样显示在骑手端的履约进度里，只能放人话。
                    // sourceEventId 是内部关联用的，detail_json 里已经有了，不要再塞进这里。
                    "订单状态同步失败，系统会自动重试",
                    null,
                    null,
                    null,
                    detail
            );
        } catch (RuntimeException ignored) {
            LOGGER.warn("订单状态同步失败事件写入失败：任务 {}", task.taskNo());
        }
    }
}
