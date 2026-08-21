package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.delivery.domain.DeliveryEvidence;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ExceptionEvidenceDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, task_id, exception_id, order_id, rider_id, evidence_type, file_url, file_size,
            width, height, lat, lng, watermark_text, captured_at, uploaded_at
            """;

    private static final RowMapper<DeliveryEvidence> ROW_MAPPER = (resultSet, rowNum) -> new DeliveryEvidence(
            resultSet.getLong("id"),
            JdbcValues.longOrNull(resultSet, "task_id"),
            JdbcValues.longOrNull(resultSet, "exception_id"),
            JdbcValues.longOrNull(resultSet, "order_id"),
            JdbcValues.longOrNull(resultSet, "rider_id"),
            resultSet.getString("evidence_type"),
            resultSet.getString("file_url"),
            JdbcValues.intOrNull(resultSet, "file_size"),
            JdbcValues.intOrNull(resultSet, "width"),
            JdbcValues.intOrNull(resultSet, "height"),
            JdbcValues.doubleOrNull(resultSet, "lat"),
            JdbcValues.doubleOrNull(resultSet, "lng"),
            resultSet.getString("watermark_text"),
            JdbcValues.dateTime(resultSet, "captured_at"),
            JdbcValues.dateTime(resultSet, "uploaded_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public ExceptionEvidenceDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(DeliveryEvidence evidence, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO delivery_evidence
                        (task_id, exception_id, order_id, rider_id, evidence_type, file_url, file_size,
                         width, height, lat, lng, watermark_text, captured_at, uploaded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            setNullableLong(statement, 1, evidence.taskId());
            setNullableLong(statement, 2, evidence.exceptionId());
            setNullableLong(statement, 3, evidence.orderId());
            setNullableLong(statement, 4, evidence.riderId());
            statement.setString(5, evidence.evidenceType());
            statement.setString(6, evidence.fileUrl());
            setNullableInt(statement, 7, evidence.fileSize());
            setNullableInt(statement, 8, evidence.width());
            setNullableInt(statement, 9, evidence.height());
            setNullableDouble(statement, 10, evidence.lat());
            setNullableDouble(statement, 11, evidence.lng());
            statement.setString(12, evidence.watermarkText());
            statement.setTimestamp(13, JdbcValues.timestamp(evidence.capturedAt()));
            statement.setTimestamp(14, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public Optional<DeliveryEvidence> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_evidence WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<ExceptionBinding> findExceptionBinding(long exceptionId) {
        return findExceptionBinding(exceptionId, false);
    }

    public Optional<ExceptionBinding> findExceptionBindingForUpdate(long exceptionId) {
        return findExceptionBinding(exceptionId, true);
    }

    private Optional<ExceptionBinding> findExceptionBinding(long exceptionId, boolean forUpdate) {
        return jdbcTemplate.query("""
                        SELECT id, task_id, rider_id FROM delivery_exception WHERE id = ?%s
                        """.formatted(forUpdate ? " FOR UPDATE" : ""),
                (resultSet, rowNum) -> new ExceptionBinding(
                        resultSet.getLong("id"),
                        JdbcValues.longOrNull(resultSet, "task_id"),
                        JdbcValues.longOrNull(resultSet, "rider_id")
                ),
                exceptionId).stream().findFirst();
    }

    public int bindToException(
            Collection<Long> evidenceIds,
            long exceptionId,
            Long taskId,
            Long riderId
    ) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return 0;
        }
        List<Long> ids = evidenceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(exceptionId);
        args.addAll(ids);
        StringBuilder sql = new StringBuilder(
                "UPDATE delivery_evidence SET exception_id = ? WHERE id IN (" + placeholders + ")"
                        + " AND exception_id IS NULL"
        );
        if (taskId == null) {
            sql.append(" AND task_id IS NULL");
        } else {
            sql.append(" AND task_id = ?");
            args.add(taskId);
        }
        if (riderId == null) {
            sql.append(" AND rider_id IS NULL");
        } else {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    public int countOwnedUnbound(Collection<Long> evidenceIds, Long taskId, Long riderId) {
        List<Long> ids = normalizedIds(evidenceIds);
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM delivery_evidence WHERE id IN (" + placeholders + ")"
                        + " AND exception_id IS NULL"
        );
        if (taskId == null) {
            sql.append(" AND task_id IS NULL");
        } else {
            sql.append(" AND task_id = ?");
            args.add(taskId);
        }
        if (riderId == null) {
            sql.append(" AND rider_id IS NULL");
        } else {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<DeliveryEvidence> findByIds(Collection<Long> evidenceIds) {
        List<Long> ids = normalizedIds(evidenceIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_evidence WHERE id IN (" + placeholders + ") ORDER BY id",
                ROW_MAPPER,
                ids.toArray()
        );
    }

    public List<DeliveryEvidence> search(Long taskId, String evidenceType, Long exceptionId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery_evidence WHERE 1 = 1");
        if (taskId != null) {
            sql.append(" AND task_id = ?");
            args.add(taskId);
        }
        if (exceptionId != null) {
            sql.append(" AND exception_id = ?");
            args.add(exceptionId);
        }
        if (evidenceType != null && !evidenceType.isBlank()) {
            sql.append(" AND evidence_type = ?");
            args.add(evidenceType.trim().toUpperCase());
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    private static List<Long> normalizedIds(Collection<Long> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        return evidenceIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.DECIMAL);
        } else {
            statement.setDouble(index, value);
        }
    }

    public record ExceptionBinding(long exceptionId, Long taskId, Long riderId) {
    }
}
