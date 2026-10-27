package com.xianda.freshdelivery.delivery.dto;

public record AssignRequest(
        Long riderId,
        Boolean force,
        String reason
) {}
