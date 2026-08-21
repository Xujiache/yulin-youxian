package com.xianda.freshdelivery.delivery.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class IntegrationJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private IntegrationJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
