package com.xianda.freshdelivery.delivery.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.xianda.freshdelivery.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class DeliveryExceptionAdviceTests {

    @Test
    void serviceUnavailableUsesHttp503AndKeepsApiEnvelope() {
        ResponseEntity<ApiResponse<Void>> response =
                DeliveryExceptionAdvice.response(new DeliveryException(503, "配送服务维护中"));

        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(503, response.getBody().code());
        assertEquals("配送服务维护中", response.getBody().message());
    }

    @Test
    void businessCodesRemainEnvelopeCompatible() {
        ResponseEntity<ApiResponse<Void>> response =
                DeliveryExceptionAdvice.response(new DeliveryException(1011, "状态已变化"));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1011, response.getBody().code());
    }
}
