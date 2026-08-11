package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record WaveSequenceRequest(
        List<Long> taskIds
) {}
