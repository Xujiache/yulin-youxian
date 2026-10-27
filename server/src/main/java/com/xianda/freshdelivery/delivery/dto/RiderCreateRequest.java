package com.xianda.freshdelivery.delivery.dto;

public record RiderCreateRequest(
        String name,
        String phone,
        String idCardMasked,
        String role,
        String vehicleType,
        String vehiclePlate,
        Integer maxConcurrentTask,
        Double capacityWeightKg,
        Integer insulatedBoxCount,
        String healthCertNo,
        String healthCertExpireAt,
        String hiredAt,
        String remark
) {}
