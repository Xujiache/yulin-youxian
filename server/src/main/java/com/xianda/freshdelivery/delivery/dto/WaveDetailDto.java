package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record WaveDetailDto(
        Long waveId,
        String waveNo,
        Long riderId,
        String riderName,
        String status,
        String deliveryDate,
        /** 配送时段。时段批次发车时写入，老波次为空。 */
        String slotLabel,
        Integer taskCount,
        Integer completedCount,
        Double totalWeightKg,
        Integer totalItemCount,
        String maxColdChainLevel,
        Integer planDistanceMeters,
        Integer planDurationSeconds,
        Integer actualDistanceMeters,
        String planReturnAt,
        String optimizerName,
        String matrixProvider,
        String assignedAt,
        String startedAt,
        String completedAt,
        /** 骑手确认回店的时间。为空且状态为 RETURNING 表示送完了但还没回到店里。 */
        String returnedAt,
        List<WaveStopDto> stops,
        WaveRouteDto route,
        List<TrackPointDto> track
) {
    public record WaveStopDto(
            Long taskId,
            Integer seqNo,
            Integer originalSeqNo,
            GeoPointDto location,
            Integer legDistanceMeters,
            Integer legDurationSeconds,
            Integer handoffEstimateSeconds,
            String planArriveAt,
            String planDepartAt,
            String actualArriveAt,
            String actualDepartAt,
            Boolean adjustedByRider
    ) {}

    public record TrackPointDto(
            Double lat,
            Double lng,
            String locatedAt
    ) {}
}
