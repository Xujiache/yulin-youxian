package com.xianda.freshdelivery.delivery.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Rider(
        Long id,
        String riderNo,
        String name,
        String phone,
        String passwordHash,
        Boolean mustChangePassword,
        String avatarUrl,
        String idCardMasked,
        String role,
        String accountStatus,
        String workStatus,
        String healthCertNo,
        LocalDate healthCertExpireAt,
        String vehiclePlate,
        String vehicleType,
        Integer maxConcurrentTask,
        BigDecimal capacityWeightKg,
        Integer insulatedBoxCount,
        Boolean probation,
        LocalDate hiredAt,
        Integer serviceScore,
        String levelCode,
        Integer totalTaskCount,
        Integer onTimeTaskCount,
        LocalDateTime locationConsentAt,
        String locationConsentVersion,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {}
