package com.xianda.freshdelivery.delivery.dto;

import java.util.Map;

public record TasksByOrdersDto(
        Map<Long, DeliveryTaskBriefDto> items
) {}
