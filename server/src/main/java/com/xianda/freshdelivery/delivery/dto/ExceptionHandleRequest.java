package com.xianda.freshdelivery.delivery.dto;

public record ExceptionHandleRequest(
        String resolutionType,
        String resolutionNote,
        Boolean riderExempt
) {}
