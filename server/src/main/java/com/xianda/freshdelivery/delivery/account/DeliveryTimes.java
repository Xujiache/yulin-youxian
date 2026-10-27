package com.xianda.freshdelivery.delivery.account;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class DeliveryTimes {
    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private DeliveryTimes() {
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }

    public static String format(LocalDate value) {
        return value == null ? null : value.toString();
    }

    public static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            LocalDate date = parseDate(text);
            return date == null ? null : date.atStartOfDay();
        }
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim().length() > 10 ? value.trim().substring(0, 10) : value.trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
