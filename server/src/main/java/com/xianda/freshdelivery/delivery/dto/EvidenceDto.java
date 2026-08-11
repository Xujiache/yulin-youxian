package com.xianda.freshdelivery.delivery.dto;

public record EvidenceDto(
        Long id,
        String fileUrl,
        String evidenceType,
        String capturedAt
) {}
