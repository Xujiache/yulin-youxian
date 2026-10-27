package com.xianda.freshdelivery.delivery.tracking;

public record DeliveryHealthDto(
        DispatchLoopHealthDto dispatchLoop,
        RiderHealthDto riders,
        TaskHealthDto tasks,
        LocationHealthDto location,
        ExternalHealthDto external,
        DatabaseHealthDto database,
        BackupHealthDto backup
) {
    public record DispatchLoopHealthDto(
            String lastRunAt,
            Long lastDurationMs,
            Integer consecutiveFailures,
            String status
    ) {
    }

    public record RiderHealthDto(Integer onDuty, Integer staleLocation) {
    }

    public record TaskHealthDto(Integer pending, Integer overtimeRisk, Integer stuckOver2h) {
    }

    public record LocationHealthDto(Integer last5MinPoints, Double rejectRate) {
    }

    public record ExternalHealthDto(String amap, String push, String privacyNumber) {
    }

    public record DatabaseHealthDto(Long riderLocationRows, String oldestLocationAt) {
    }

    public record BackupHealthDto(String lastAt, Boolean deliveryTablesIncluded) {
    }
}
