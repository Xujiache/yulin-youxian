package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record DispatchSuggestRequest(
        List<Long> taskIds
) {}
