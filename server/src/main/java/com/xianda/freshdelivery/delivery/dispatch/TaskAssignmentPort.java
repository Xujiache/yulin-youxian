package com.xianda.freshdelivery.delivery.dispatch;

public interface TaskAssignmentPort {

    void assignTask(long taskId, long riderId, Long waveId, String dispatchMode, Double dispatchScore,
                    String detailJson);

    /**
     * 批量发车时用：只落库，不重算 ETA、不逐条推送。
     *
     * 一波 20 单如果走 {@link #assignTask}，事务里会重算 20 遍 ETA，骑手还会连收 20 条
     * 「新任务待接单」语音——跟「一键全接」完全对着干。调用方在事务外统一规划一次、通知一次。
     */
    default void assignTaskQuietly(long taskId, long riderId, Long waveId, String dispatchMode,
                                   Double dispatchScore, String detailJson) {
        assignTask(taskId, riderId, waveId, dispatchMode, dispatchScore, detailJson);
    }

    void reassignTask(long taskId, long toRiderId, String reason, String operatorType, String operatorName);
}
