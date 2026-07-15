package com.xianda.freshdelivery.dto;

import jakarta.validation.constraints.NotBlank;

public record WxLoginRequest(
        @NotBlank String code,
        String clientId,
        String nickName,
        String avatarUrl
) {
}
