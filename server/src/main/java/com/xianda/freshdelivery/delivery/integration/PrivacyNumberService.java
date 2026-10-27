package com.xianda.freshdelivery.delivery.integration;

import java.time.LocalDateTime;

public interface PrivacyNumberService {
    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_RELEASED = "RELEASED";
    String STATUS_FAILED = "FAILED";
    String STATUS_DEGRADED = "DEGRADED";

    String provider();

    boolean available();

    PrivacyBinding bind(BindRequest request);

    void release(long bindingId, String subscriptionId);

    record BindRequest(
            long taskId,
            long riderId,
            String riderPhone,
            String customerPhone,
            LocalDateTime expireAt
    ) {
    }

    record PrivacyBinding(
            String provider,
            String subscriptionId,
            String callNumber,
            boolean degraded,
            String status,
            String notice
    ) {
    }
}
