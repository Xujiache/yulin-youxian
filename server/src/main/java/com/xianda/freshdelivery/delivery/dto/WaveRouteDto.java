package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record WaveRouteDto(
        Long waveId,
        Integer planVersion,
        String optimizerName,
        String matrixProvider,
        OriginDto origin,
        List<RouteStopDto> stops,
        Integer totalDistanceMeters,
        Integer totalDurationSeconds,
        String polyline
) {
    public record OriginDto(
            Double lat,
            Double lng,
            String name
    ) {}

    public record RouteStopDto(
            Long taskId,
            Integer seqNo,
            GeoPointDto location,
            Integer legDistanceMeters,
            Integer legDurationSeconds,
            Integer handoffEstimateSeconds,
            String planArriveAt,
            String planDepartAt
    ) {}
}
