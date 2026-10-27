package com.xianda.freshdelivery.delivery.dto;

public record CallNumberDto(
        String callNumber,
        Boolean degraded,
        String expireAt,
        String notice
) {}
