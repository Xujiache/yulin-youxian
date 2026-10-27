package com.xianda.freshdelivery.delivery.dto;

public record RiderLoginResponse(
        String accessToken,
        String refreshToken,
        String accessExpireAt,
        Boolean mustChangePassword,
        Boolean locationConsentRequired,
        RiderProfileDto rider
) {}
