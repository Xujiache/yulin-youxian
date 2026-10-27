package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record LocationBatchResponse(
        Integer accepted,
        Integer rejected,
        List<RejectReasonDto> rejectReasons,
        String serverTime,
        Integer nextIntervalSeconds,
        List<CommandDto> commands
) {
    public record RejectReasonDto(
            Integer index,
            String reason
    ) {}

    public record CommandDto(
            String type,
            Long taskId
    ) {}
}
