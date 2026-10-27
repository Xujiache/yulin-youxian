package com.xianda.freshdelivery.delivery.integration;

public interface PushService {
    String STATUS_SENT = "SENT";
    String STATUS_FAILED = "FAILED";
    String STATUS_SKIPPED = "SKIPPED";

    String provider();

    boolean available();

    PushResult push(PushRequest request);

    record PushRequest(
            Long riderId,
            String messageType,
            String title,
            String content,
            String priority,
            boolean needVoice,
            String linkType,
            String linkTarget,
            Long messageId
    ) {
    }

    record PushResult(String status, String error) {
        public static PushResult sent() {
            return new PushResult(STATUS_SENT, null);
        }

        public static PushResult skipped(String reason) {
            return new PushResult(STATUS_SKIPPED, reason);
        }

        public static PushResult failed(String error) {
            return new PushResult(STATUS_FAILED, error);
        }

        public boolean sentSuccessfully() {
            return STATUS_SENT.equals(status);
        }
    }
}
