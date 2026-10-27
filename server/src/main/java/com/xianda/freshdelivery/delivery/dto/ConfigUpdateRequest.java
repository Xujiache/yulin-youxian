package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record ConfigUpdateRequest(
        List<ConfigItem> items
) {
    public record ConfigItem(
            String key,
            String value
    ) {}
}
