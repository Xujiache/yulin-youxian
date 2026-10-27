package com.xianda.freshdelivery.delivery.dto;

public record WeightCheckJudgeRequest(
        String verdict,
        Integer refundAmount,
        String note
) {}
