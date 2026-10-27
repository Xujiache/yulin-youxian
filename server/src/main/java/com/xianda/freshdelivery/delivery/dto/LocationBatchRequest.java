package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record LocationBatchRequest(
        String batchKey,
        List<LocationPointDto> points,
        Long currentTaskId,
        Long waveId
) {
    public record LocationPointDto(
            Double lat,
            Double lng,
            Integer accuracyMeters,
            Double speedMps,
            Double bearing,
            Double altitude,
            String provider,
            String motionState,
            Integer batteryLevel,
            String networkType,
            String locatedAt
    ) {}
}
