package com.xianda.freshdelivery.delivery.dto;

public record FatigueDto(
        String level,
        String message,
        String dispatchPausedUntil,
        Boolean needConfirm,
        Boolean forceOffDuty
) {}
