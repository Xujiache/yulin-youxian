package com.xianda.freshdelivery.delivery.dto;

public record ChangePasswordRequest(
        String oldPassword,
        String newPassword
) {}
