package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskStateMachine {

    public boolean canTransit(DeliveryTaskStatus from, DeliveryTaskStatus to) {
        return from != null && from.canTransitTo(to);
    }

    public void ensureTransit(DeliveryTaskStatus from, DeliveryTaskStatus to) {
        if (!canTransit(from, to)) {
            throw new DeliveryException(
                    DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                    "任务状态不允许该操作：" + display(from) + " → " + display(to)
            );
        }
    }

    public List<DeliveryTaskStatus> resolvePath(DeliveryTaskStatus from, DeliveryTaskStatus to) {
        if (canTransit(from, to)) {
            return List.of(to);
        }
        if (from != null
                && from.canTransitTo(DeliveryTaskStatus.EXCEPTION)
                && DeliveryTaskStatus.EXCEPTION.canTransitTo(to)) {
            return List.of(DeliveryTaskStatus.EXCEPTION, to);
        }
        throw new DeliveryException(
                DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED,
                "任务状态不允许该操作：" + display(from) + " → " + display(to)
        );
    }

    public void ensureOwnedBy(DeliveryTask task, long riderId) {
        if (task == null) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND);
        }
        if (task.riderId() == null || task.riderId() != riderId) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_OWNED_BY_RIDER);
        }
    }

    public DeliveryTaskStatus statusOf(DeliveryTask task) {
        if (task == null) {
            throw new DeliveryException(DeliveryErrorCode.TASK_NOT_FOUND);
        }
        return parse(task.status());
    }

    public DeliveryTaskStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "任务状态为空");
        }
        try {
            return DeliveryTaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new DeliveryException(DeliveryErrorCode.TASK_STATUS_NOT_ALLOWED, "未知任务状态：" + raw);
        }
    }

    public boolean isTerminal(String raw) {
        return parse(raw).isTerminal();
    }

    private String display(DeliveryTaskStatus status) {
        return status == null ? "未知" : status.displayName();
    }
}
