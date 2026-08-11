package com.xianda.freshdelivery.delivery.dto;

public record ReassignRequest(
        Long toRiderId,
        String reason
) {}
