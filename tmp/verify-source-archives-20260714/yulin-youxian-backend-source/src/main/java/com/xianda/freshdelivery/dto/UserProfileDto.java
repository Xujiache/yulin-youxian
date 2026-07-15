package com.xianda.freshdelivery.dto;

import java.util.List;

public record UserProfileDto(
        Long userId,
        String nickName,
        String avatarUrl,
        Boolean profileCompleted,
        List<OrderStatusCountDto> orderStats
) {
}
