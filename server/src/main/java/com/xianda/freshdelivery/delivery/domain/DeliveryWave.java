package com.xianda.freshdelivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record DeliveryWave(
        Long id,
        String waveNo,
        Long riderId,
        String status,
        LocalDate deliveryDate,
        /** 配送时段，与 delivery_task.slot_label 一致。时段批次发车时写入。 */
        String slotLabel,
        Integer taskCount,
        Integer completedCount,
        BigDecimal totalWeightKg,
        Integer totalItemCount,
        String maxColdChainLevel,
        Integer planDistanceMeters,
        Integer planDurationSeconds,
        Integer actualDistanceMeters,
        LocalDateTime planReturnAt,
        Long routePlanId,
        String optimizerName,
        String matrixProvider,
        LocalDateTime assignedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        /** 骑手确认回到门店的时间。为空表示还没回店，调度台据此判断能不能发下一个时段。 */
        LocalDateTime returnedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
