package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record WaveReplayDto(
        Long waveId,
        String waveNo,
        Double speed,
        String from,
        String to,
        List<WaveDetailDto.TrackPointDto> points,
        List<WaveDetailDto.WaveStopDto> stops
) {}
