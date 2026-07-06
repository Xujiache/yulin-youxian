package com.xianda.freshdelivery.dto;

public record UserProfileUpdateRequest(
        String nickName,
        String avatarUrl
) {
}
