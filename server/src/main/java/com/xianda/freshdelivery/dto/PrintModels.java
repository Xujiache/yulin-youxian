package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class PrintModels {
    private PrintModels() {
    }

    public record PrinterConfigDto(
            boolean enabled,
            boolean autoPrintOnPaid,
            int retryLimit,
            String printerModel,
            boolean accessKeyConfigured,
            boolean agentOnline,
            String agentName,
            String agentConnection,
            String agentVersion,
            String agentLastSeen,
            int pendingJobCount,
            int failedJobCount
    ) {
    }

    public record PrinterConfigUpdateRequest(
            @NotNull Boolean enabled,
            @NotNull Boolean autoPrintOnPaid,
            @NotNull @Min(1) @Max(10) Integer retryLimit,
            String printerModel
    ) {
    }

    public record PrinterAccessKeyDto(String accessKey) {
    }

    public record PrintReceiptItemDto(
            String name,
            String quantity,
            String unitPrice,
            String amount
    ) {
    }

    public record PrintReceiptDto(
            String storeName,
            String title,
            String orderNo,
            String createdAt,
            String deliverySlot,
            String customerName,
            String customerPhone,
            String address,
            List<PrintReceiptItemDto> items,
            String productAmount,
            String deliveryFee,
            String packageFee,
            String payableAmount,
            String remark
    ) {
    }

    public record PrintJobDto(
            Long id,
            Long orderId,
            String type,
            String orderNo,
            String status,
            int attemptCount,
            int retryLimit,
            String createdAt,
            String nextAttemptAt,
            String printedAt,
            String lastError,
            String leaseToken,
            PrintReceiptDto receipt
    ) {
    }

    public record PrintJobResultRequest(
            String leaseToken,
            @NotNull Boolean success,
            String message
    ) {
    }

    public record PrintAgentHeartbeatRequest(
            String agentName,
            String connection,
            String version
    ) {
    }
}
