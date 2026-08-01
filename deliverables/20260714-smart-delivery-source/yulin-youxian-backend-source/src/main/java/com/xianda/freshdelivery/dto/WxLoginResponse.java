package com.xianda.freshdelivery.dto;

public record WxLoginResponse(
        String token,
        Long userId,
        String openId,
        String nickName,
        String avatarUrl,
        Boolean profileCompleted
) {
}
