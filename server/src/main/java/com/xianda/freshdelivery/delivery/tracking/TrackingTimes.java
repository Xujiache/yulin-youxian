package com.xianda.freshdelivery.delivery.tracking;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class TrackingTimes {
    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private TrackingTimes() {
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }

    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim().replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    public static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public static long secondsBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0L;
        }
        return java.time.Duration.between(from, to).getSeconds();
    }
}
