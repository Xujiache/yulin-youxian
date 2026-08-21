package com.xianda.freshdelivery.delivery.routing;

import com.xianda.freshdelivery.delivery.domain.RoutePlan;
import java.util.Optional;

public interface RoutePlanDao {
    int nextPlanVersion(long waveId);

    void deactivate(long waveId);

    long insert(RoutePlan plan);

    Optional<RoutePlan> findActive(long waveId);
}
