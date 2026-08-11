package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record ExceptionDto(
        Long exceptionId,
        String exceptionNo,
        Long taskId,
        String exceptionType,
        String severity,
        String status,
        String description,
        Boolean riderExempt,
        String holdUntilAt,
        String guidance,
        List<String> allowedNextActions,
        String resolutionType,
        String resolutionNote,
        String createdAt
) {}
