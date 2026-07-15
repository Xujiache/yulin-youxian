package com.xianda.freshdelivery.dto;

public record AdminCustomerDto(
        Long userId,
        String nickName,
        String avatarUrl,
        Integer orderCount
) {
}
