package com.xianda.freshdelivery.delivery.dispatch;

import java.time.LocalDate;

public record DispatchWaveRow(
        long waveId,
        String waveNo,
        Long riderId,
        String status,
        LocalDate deliveryDate,
        int taskCount
) {
    public boolean openForAppend() {
        return "PLANNING".equals(status) || "ASSIGNED".equals(status);
    }
}
