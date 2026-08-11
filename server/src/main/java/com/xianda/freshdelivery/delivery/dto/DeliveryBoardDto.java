package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record DeliveryBoardDto(
        String serverTime,
        BoardSummaryDto summary,
        BoardQueuesDto queues,
        List<RiderBoardCardDto> riders,
        List<WaveBriefDto> waves
) {
    public record BoardSummaryDto(
            Integer pendingCount,
            Integer assignedCount,
            Integer deliveringCount,
            Integer overtimeRiskCount,
            Integer openExceptionCount,
            Integer onDutyRiderCount,
            Integer availableRiderCount,
            Boolean capacityWarning,
            Integer avgDeliveryMinutes,
            Double onTimeRateToday
    ) {}

    public record BoardQueuesDto(
            List<AdminTaskCardDto> pending,
            List<AdminTaskCardDto> overtimeRisk,
            List<AdminTaskCardDto> openException,
            List<RiderBoardCardDto> idleRiders
    ) {}

    public record AdminTaskCardDto(
            Long taskId,
            String taskNo,
            String orderNo,
            String status,
            String statusText,
            Integer seqNo,
            Integer totalStops,
            String receiverName,
            String receiverPhoneMasked,
            String receiverPhone,
            String callNumber,
            Boolean phoneDegraded,
            String addressDetail,
            String areaLabel,
            String buildingLabel,
            Integer unitNo,
            Integer floorNo,
            String roomNo,
            GeoPointDto location,
            Integer distanceFromRiderMeters,
            Integer itemCount,
            Double totalWeightKg,
            Integer packageCount,
            String coldChainLevel,
            String coldChainText,
            String goodsSummary,
            String customerRemark,
            String deliveryInstruction,
            List<String> highlightNotes,
            String slotLabel,
            String promisedAt,
            String etaAt,
            Integer remainingSeconds,
            String overtimeRisk,
            Boolean requireVerifyCode,
            Boolean requirePhoto,
            Integer sameAddressTaskCount,
            Long riderId,
            String riderName,
            Double dispatchScore,
            Integer reassignCount,
            String holdUntilAt,
            String createdAt,
            String pickedReadyAt,
            String waveNo
    ) {}

    public record RiderBoardCardDto(
            Long riderId,
            String riderNo,
            String name,
            String avatarUrl,
            String workStatus,
            Integer onDutySeconds,
            GeoPointDto location,
            String locatedAt,
            Boolean locationStale,
            Integer batteryLevel,
            Long currentWaveId,
            Integer currentTaskCount,
            Integer maxConcurrentTask,
            Double loadRatio,
            Double currentWeightKg,
            Double capacityWeightKg,
            Integer todayDeliveredCount,
            Double todayOnTimeRate,
            Integer serviceScore,
            Boolean probation,
            String fatigueLevel,
            String dispatchPausedUntil,
            String planReturnAt
    ) {}

    public record WaveBriefDto(
            Long waveId,
            String waveNo,
            Long riderId,
            String riderName,
            String status,
            Integer taskCount,
            Integer completedCount,
            Integer planDistanceMeters,
            String planReturnAt,
            String maxColdChainLevel
    ) {}
}
