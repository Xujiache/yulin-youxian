package com.xianda.freshdelivery.delivery.dispatch;

public interface WaveRoutingPort {

    void planWave(long waveId, boolean newTaskJoinedExistingWave);

    void replanAfterReassign(long waveId);

    void recomputeWaveEta(long waveId);
}
