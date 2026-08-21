package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record WaveCreateRequest(
        Long riderId,
        List<Long> taskIds
) {}
