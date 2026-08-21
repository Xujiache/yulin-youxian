package com.xianda.freshdelivery.delivery.dto;

public record LocationConsentRequest(
        Boolean agreed,
        String consentVersion,
        String agreedAt
) {}
