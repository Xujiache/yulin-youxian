package com.xianda.freshdelivery.delivery.task;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

final class TaskRowReader {

    private TaskRowReader() {
    }

    static Long longValue(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer intValue(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Double doubleValue(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    static BigDecimal decimalValue(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    static Boolean booleanValue(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value != 0;
    }

    static LocalDateTime dateTimeValue(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    static LocalDate dateValue(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    static java.sql.Date date(LocalDate value) {
        return value == null ? null : java.sql.Date.valueOf(value);
    }

    static Integer flag(Boolean value) {
        return value == null ? null : (value ? 1 : 0);
    }
}
