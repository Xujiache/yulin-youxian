package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

public abstract class TrackingControllerSupport {

    @ExceptionHandler(DeliveryException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeliveryException(DeliveryException exception) {
        return com.xianda.freshdelivery.delivery.common.DeliveryExceptionAdvice.response(exception);
    }

    protected static LocalDateTime parseDateTime(String raw, LocalDateTime fallback) {
        LocalDateTime parsed = TrackingTimes.parse(raw);
        if (parsed != null) {
            return parsed;
        }
        if (raw != null && !raw.isBlank()) {
            try {
                return LocalDate.parse(raw.trim()).atStartOfDay();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
