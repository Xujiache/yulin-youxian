package com.xianda.freshdelivery.delivery.dto;

public record RiderUpdateRequest(
        String name,
        String phone,
        String avatarUrl,
        String idCardMasked,
        String role,
        String vehicleType,
        String vehiclePlate,
        Integer maxConcurrentTask,
        Double capacityWeightKg,
        Integer insulatedBoxCount,
        Boolean probation,
        String levelCode,
        String healthCertNo,
        String healthCertExpireAt,
        String hiredAt,
        String remark
) {}
