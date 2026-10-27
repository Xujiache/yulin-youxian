package com.xianda.freshdelivery.delivery.dto;

import java.util.List;

public record SubscribeRequest(
        List<String> templateIds
) {}
