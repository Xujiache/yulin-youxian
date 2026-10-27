package com.xianda.freshdelivery.delivery.dto;

public record FatigueConfirmRequest(
        Boolean confirmed,
        Integer extendMinutes
) {}
