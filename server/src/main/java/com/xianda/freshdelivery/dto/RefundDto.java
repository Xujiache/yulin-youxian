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
        String paymentOrderNo
) {
    public RefundDto {
        evidenceImages = evidenceImages == null ? List.of() : List.copyOf(evidenceImages);
        failureCode = failureCode == null ? "" : failureCode;
        failureMessage = failureMessage == null ? "" : failureMessage;
        retryCount = retryCount == null ? 0 : retryCount;
        lastAttemptAt = lastAttemptAt == null ? "" : lastAttemptAt;
        paymentOrderNo = paymentOrderNo == null ? "" : paymentOrderNo;
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
