package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

public abstract class DeliveryTaskControllerSupport {

    @ExceptionHandler(DeliveryException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeliveryException(DeliveryException exception) {
        return com.xianda.freshdelivery.delivery.common.DeliveryExceptionAdvice.response(exception);
    }

    protected static List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(token));
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
        return ids;
    }

    protected static LocalDate parseDate(String raw) {
        return TaskTimes.parseDate(raw);
    }
}
