package com.xianda.freshdelivery.delivery.dto;

public record AppealReviewRequest(
        Boolean approved,
        String reviewNote
) {}
