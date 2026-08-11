package com.xianda.freshdelivery.delivery.routing;

import java.time.LocalDateTime;

public interface RoutingEtaDao {
    void updateEta(long taskId, LocalDateTime etaAt, LocalDateTime etaLowerAt, LocalDateTime etaUpperAt,
                   LocalDateTime etaUpdatedAt);

    void addExtraTime(long taskId, int deltaSeconds, String reason);
}
