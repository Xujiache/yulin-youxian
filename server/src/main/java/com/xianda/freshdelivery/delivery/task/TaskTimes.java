package com.xianda.freshdelivery.delivery.task;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TaskTimes {
    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private TaskTimes() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(STORE_ZONE).withNano(0);
    }

    public static LocalDate today() {
        return LocalDate.now(STORE_ZONE);
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(ISO_SECONDS);
    }

    public static String format(LocalDate value) {
        return value == null ? null : value.toString();
    }

    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() > 19) {
            text = text.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(text.length() == 16 ? text + ":00" : text, ISO_SECONDS);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
