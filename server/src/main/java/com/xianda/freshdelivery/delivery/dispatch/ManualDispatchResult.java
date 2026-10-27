package com.xianda.freshdelivery.delivery.dispatch;

import java.util.List;

public record ManualDispatchResult(
        List<Long> assignedTaskIds,
        List<Long> skippedTaskIds,
        Long waveId,
        Long riderId,
        Double score,
        boolean forced,
        List<String> warnings
) {
    public ManualDispatchResult {
        assignedTaskIds = List.copyOf(assignedTaskIds);
        skippedTaskIds = List.copyOf(skippedTaskIds);
        warnings = List.copyOf(warnings);
    }
}
