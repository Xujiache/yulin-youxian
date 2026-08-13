package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.common.DeliveryTaskStatus;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskStateMachine {

    /**
     * 「先进异常、再到目标」这条两步修复通道允许的终点。
     *
     * 异常态本身能转出的状态比这个集合宽（解除异常要能回到进入异常之前的那一步），
     * 但那是 resolveException 明确指定来源状态时才走的单步转换。
     * 两步修复是给一个本来非法的跳转找台阶下，必须收窄到「继续送 / 退回 / 取消」，
     * 否则「已派单 →（异常）→ 已到达」这种跳过接单和取货的路径就会被放行。
     */
    private static final Set<DeliveryTaskStatus> EXCEPTION_REPAIR_TARGETS = EnumSet.of(
            DeliveryTaskStatus.DELIVERING,
            DeliveryTaskStatus.RETURNED,
            DeliveryTaskStatus.CANCELLED
    );

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
                && EXCEPTION_REPAIR_TARGETS.contains(to)) {
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
