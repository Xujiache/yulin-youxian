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
            // 单独报「待回店」的人数：这些人不计入 availableRiderCount，
            // 不报出来调度员只会看到可用骑手是 0，不知道再等几分钟就有人了。
            Integer returningRiderCount,
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
            /** 配送日 yyyy-MM-dd，与 slotLabel 一起标识「哪一天的哪个时段」 */
            String deliveryDate,
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
            // 波次状态要跟到卡片上：待回店的骑手未终结任务数为 0，
            // 只看 currentTaskCount 会和真正空闲的人长得一模一样。
            String currentWaveStatus,
            Boolean returningToStore,
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
