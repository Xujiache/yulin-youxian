package com.xianda.freshdelivery.delivery.common;

import com.xianda.freshdelivery.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.xianda.freshdelivery.delivery.controller")
public class DeliveryExceptionAdvice {

    @ExceptionHandler(DeliveryException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeliveryException(DeliveryException exception) {
        return response(exception);
    }

    public static ResponseEntity<ApiResponse<Void>> response(DeliveryException exception) {
        ApiResponse<Void> envelope = ApiResponse.fail(exception.code(), exception.getMessage());
        return exception.code() == HttpStatus.SERVICE_UNAVAILABLE.value()
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(envelope)
                : ResponseEntity.ok(envelope);
    }
}
