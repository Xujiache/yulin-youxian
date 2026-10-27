package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record BatchAssignRequest(
        List<Long> taskIds,
        Long riderId,
        Boolean createWave
) {}
