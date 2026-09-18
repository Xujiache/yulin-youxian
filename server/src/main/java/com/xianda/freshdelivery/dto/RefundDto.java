package com.xianda.freshdelivery.dto;

import java.util.List;

public record RefundDto(
        Long id,
        Long orderId,
        String refundNo,
        Integer refundAmount,
        String reason,
        String status,
        List<String> evidenceImages,
        Long userId,
        String orderNo,
        String source,
        String createdAt,
        String failureCode,
        String failureMessage,
        Integer retryCount,
        String lastAttemptAt,
        String paymentOrderNo,
        List<RefundItemDto> items,
        String requestKey
) {
    public RefundDto {
        evidenceImages = evidenceImages == null ? List.of() : List.copyOf(evidenceImages);
        failureCode = failureCode == null ? "" : failureCode;
        failureMessage = failureMessage == null ? "" : failureMessage;
        retryCount = retryCount == null ? 0 : retryCount;
        lastAttemptAt = lastAttemptAt == null ? "" : lastAttemptAt;
        paymentOrderNo = paymentOrderNo == null ? "" : paymentOrderNo;
        items = items == null ? List.of() : List.copyOf(items);
        requestKey = requestKey == null ? "" : requestKey;
    }

    public RefundDto(Long id, Long orderId, String refundNo, Integer refundAmount, String reason,
                     String status, List<String> evidenceImages, Long userId, String orderNo,
                     String source, String createdAt, String failureCode, String failureMessage,
                     Integer retryCount, String lastAttemptAt, String paymentOrderNo, List<RefundItemDto> items) {
        this(id, orderId, refundNo, refundAmount, reason, status, evidenceImages, userId, orderNo,
                source, createdAt, failureCode, failureMessage, retryCount, lastAttemptAt, paymentOrderNo, items, "");
    }

    public RefundDto withRequestKey(String key) {
        return new RefundDto(id, orderId, refundNo, refundAmount, reason, status, evidenceImages, userId,
                orderNo, source, createdAt, failureCode, failureMessage, retryCount, lastAttemptAt, paymentOrderNo, items, key);
    }

    public RefundDto(Long id, Long orderId, String refundNo, Integer refundAmount, String reason,
                     String status, List<String> evidenceImages, Long userId, String orderNo,
                     String source, String createdAt, String failureCode, String failureMessage,
                     Integer retryCount, String lastAttemptAt, String paymentOrderNo) {
        this(id, orderId, refundNo, refundAmount, reason, status, evidenceImages, userId, orderNo,
                source, createdAt, failureCode, failureMessage, retryCount, lastAttemptAt, paymentOrderNo, List.of());
    }

    public RefundDto(
            Long id,
            Long orderId,
            String refundNo,
            Integer refundAmount,
            String reason,
            String status,
            List<String> evidenceImages,
            Long userId,
            String orderNo,
            String source,
            String createdAt
    ) {
        this(
                id,
                orderId,
                refundNo,
                refundAmount,
                reason,
                status,
                evidenceImages,
                userId,
                orderNo,
                source,
                createdAt,
                "",
                "",
                0,
                "",
                ""
        );
    }
}
