package com.xianda.freshdelivery.delivery.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.support.KeyHolder;

public final class JdbcValues {

    private JdbcValues() {
    }

    public static LocalDateTime dateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public static LocalDate date(ResultSet resultSet, String column) throws SQLException {
        Date value = resultSet.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    public static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    public static Integer intOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    public static Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    public static BigDecimal decimal(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getBigDecimal(column);
    }

    public static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public static Date sqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    /**
     * Reads an INSERT id without KeyHolder#getKey(), which throws when a JDBC
     * driver returns additional generated/default columns such as created_at.
     */
    public static long generatedId(KeyHolder keyHolder) {
        Long id = generatedIdOrNull(keyHolder);
        if (id == null) {
            throw new IllegalStateException("数据库未返回新增记录 id");
        }
        return id;
    }

    public static Long generatedIdOrNull(KeyHolder keyHolder) {
        List<Map<String, Object>> rows = keyHolder == null ? List.of() : keyHolder.getKeyList();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey())) {
                    return asLong(entry.getValue());
                }
            }
        }
        if (rows.size() == 1 && rows.get(0).size() == 1) {
            return asLong(rows.get(0).values().iterator().next());
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence text && !text.toString().isBlank()) {
            try {
                return Long.valueOf(text.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
