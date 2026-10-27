package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record DeliveryMapDto(
        List<MapRiderDto> riders,
        List<MapTaskDto> tasks
) {
    public record MapRiderDto(
            Long riderId,
            Double lat,
            Double lng,
            Double bearing,
            String locatedAt
    ) {}

    public record MapTaskDto(
            Long taskId,
            Double lat,
            Double lng,
            String status
    ) {}
}
