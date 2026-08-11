package com.xianda.freshdelivery.delivery.dto;

public record SettlementAdjustRequest(
        Integer adjustAmount,
        String remark
) {}
