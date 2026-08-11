package com.xianda.freshdelivery.delivery.dispatch;

public interface TaskAssignmentPort {

    void assignTask(long taskId, long riderId, Long waveId, String dispatchMode, Double dispatchScore,
                    String detailJson);

    void reassignTask(long taskId, long toRiderId, String reason, String operatorType, String operatorName);
}
