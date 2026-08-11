package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record RatingRequest(
        Integer star,
        List<String> tags,
        String comment
) {}
