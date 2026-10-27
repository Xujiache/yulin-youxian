package com.xianda.freshdelivery.delivery.dto;

public record RiderProfileDto(
        Long id,
        String riderNo,
        String name,
        String phone,
        String avatarUrl,
        String role,
        String accountStatus,
        String workStatus,
        String vehicleType,
        String vehiclePlate,
        Integer maxConcurrentTask,
        Double capacityWeightKg,
        Boolean probation,
        Integer serviceScore,
        String levelCode,
        Integer totalTaskCount,
        Double onTimeRate,
        String healthCertExpireAt,
        Boolean healthCertExpiringSoon,
        String locationConsentAt
) {}
