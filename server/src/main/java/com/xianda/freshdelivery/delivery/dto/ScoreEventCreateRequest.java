package com.xianda.freshdelivery.delivery.dto;

public record ScoreEventCreateRequest(
        Long riderId,
        Integer scoreDelta,
        String reason
) {}
