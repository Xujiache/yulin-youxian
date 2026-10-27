package com.xianda.freshdelivery.delivery.tracking;

public interface TrackingPrivacyNumberPort {

    PrivacyCallNumber callNumberForCustomer(long taskId, Long riderId, String riderPhone);

    record PrivacyCallNumber(String number, boolean degraded) {
    }
}
