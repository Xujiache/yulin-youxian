package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class DeliveryTaskAssignmentAdapter implements TaskAssignmentPort {
    private final ObjectProvider<DeliveryTaskService> taskServiceProvider;

    public DeliveryTaskAssignmentAdapter(ObjectProvider<DeliveryTaskService> taskServiceProvider) {
        this.taskServiceProvider = taskServiceProvider;
    }

    @Override
    public void assignTask(long taskId, long riderId, Long waveId, String dispatchMode, Double dispatchScore,
                           String detailJson) {
        require().assignTask(taskId, riderId, waveId, dispatchMode, dispatchScore, detailJson);
    }

    @Override
    public void reassignTask(long taskId, long toRiderId, String reason, String operatorType, String operatorName) {
        require().reassignTask(taskId, toRiderId, reason, operatorType, operatorName);
    }

    private DeliveryTaskService require() {
        DeliveryTaskService service = taskServiceProvider.getIfAvailable();
        if (service == null) {
            throw new DeliveryException(DeliveryErrorCode.ORDER_NOT_DISPATCHABLE, "配送任务服务不可用，派单已中止");
        }
        return service;
    }
}
