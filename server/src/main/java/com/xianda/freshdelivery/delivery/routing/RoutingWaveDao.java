package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RoutingWaveDao {
    Optional<RoutingWaveRow> findWave(long waveId);

    List<RoutingTaskRow> findTasksByWave(long waveId);

    Optional<RoutingTaskRow> findTask(long taskId);

    List<RoutingStopRow> findStops(long waveId);

    void upsertStop(RoutingStopRow stop);

    void updateWaveSummary(long waveId, int planDistanceMeters, int planDurationSeconds,
                           LocalDateTime planReturnAt, long routePlanId,
                           String optimizerName, String matrixProvider);
}
