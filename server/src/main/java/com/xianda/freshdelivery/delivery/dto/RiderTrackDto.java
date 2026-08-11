package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record RiderTrackDto(
        Long riderId,
        String date,
        List<TrackPointDto> points
) {
    public record TrackPointDto(
            Double lat,
            Double lng,
            Double speedMps,
            Double bearing,
            String motionState,
            String locatedAt
    ) {}
}
