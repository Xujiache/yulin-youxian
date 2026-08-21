package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.delivery.domain.DeliveryExceptionRecord;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ExceptionRecordDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, exception_no, task_id, wave_id, rider_id, order_id, exception_type, severity, status,
            source, description, lat, lng, hold_until_at, resolution_type, resolution_note, rider_exempt,
            handled_by, handled_at, client_event_at, created_at, updated_at
            """;

    private static final RowMapper<DeliveryExceptionRecord> ROW_MAPPER = (resultSet, rowNum) -> new DeliveryExceptionRecord(
            resultSet.getLong("id"),
            resultSet.getString("exception_no"),
            JdbcValues.longOrNull(resultSet, "task_id"),
            JdbcValues.longOrNull(resultSet, "wave_id"),
            JdbcValues.longOrNull(resultSet, "rider_id"),
            JdbcValues.longOrNull(resultSet, "order_id"),
            resultSet.getString("exception_type"),
            resultSet.getString("severity"),
            resultSet.getString("status"),
            resultSet.getString("source"),
            resultSet.getString("description"),
            JdbcValues.doubleOrNull(resultSet, "lat"),
            JdbcValues.doubleOrNull(resultSet, "lng"),
            JdbcValues.dateTime(resultSet, "hold_until_at"),
            resultSet.getString("resolution_type"),
            resultSet.getString("resolution_note"),
            resultSet.getBoolean("rider_exempt"),
            resultSet.getString("handled_by"),
            JdbcValues.dateTime(resultSet, "handled_at"),
            JdbcValues.dateTime(resultSet, "client_event_at"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public ExceptionRecordDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等键必须和记录本身同一条 INSERT 落库。
     *
     * 先插入再补一条 UPDATE 的写法，会让并发的同键上报都插入成功，
     * 直到后来的那条 UPDATE 撞上唯一索引才失败，对骑手端表现为 500 而不是重放。
     */
    public long insert(DeliveryExceptionRecord record, String clientEventId, LocalDateTime now) {
        String idempotencyKey = clientEventId == null || clientEventId.isBlank() ? null : clientEventId.trim();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO delivery_exception
                        (exception_no, task_id, wave_id, rider_id, order_id, exception_type, severity, status,
                         source, description, lat, lng, hold_until_at, rider_exempt, client_event_id,
                         client_event_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setString(1, record.exceptionNo());
            setNullableLong(statement, 2, record.taskId());
            setNullableLong(statement, 3, record.waveId());
            setNullableLong(statement, 4, record.riderId());
            setNullableLong(statement, 5, record.orderId());
            statement.setString(6, record.exceptionType());
            statement.setString(7, record.severity());
            statement.setString(8, record.status());
            statement.setString(9, record.source());
            statement.setString(10, record.description());
            setNullableDouble(statement, 11, record.lat());
            setNullableDouble(statement, 12, record.lng());
            statement.setTimestamp(13, JdbcValues.timestamp(record.holdUntilAt()));
            statement.setBoolean(14, !Boolean.FALSE.equals(record.riderExempt()));
            statement.setString(15, idempotencyKey);
            statement.setTimestamp(16, JdbcValues.timestamp(record.clientEventAt()));
            statement.setTimestamp(17, JdbcValues.timestamp(now));
            statement.setTimestamp(18, JdbcValues.timestamp(now));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public String maxExceptionNoWithPrefix(String prefix) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT MAX(exception_no) FROM delivery_exception WHERE exception_no LIKE ?",
                String.class, prefix + "%");
        return values.isEmpty() ? null : values.get(0);
    }

    public Optional<DeliveryExceptionRecord> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_exception WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<DeliveryExceptionRecord> findByIdForUpdate(long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_exception WHERE id = ? FOR UPDATE",
                ROW_MAPPER, id).stream().findFirst();
    }

    /** 幂等重放查找。骑手端带同一个 clientEventId 重试时不能再插一条。 */
    public Optional<DeliveryExceptionRecord> findByClientEventId(String clientEventId) {
        if (clientEventId == null || clientEventId.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_exception WHERE client_event_id = ?",
                ROW_MAPPER, clientEventId.trim()).stream().findFirst();
    }

    public List<DeliveryExceptionRecord> findByRider(long riderId, String status, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery_exception WHERE rider_id = ?");
        args.add(riderId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long countByRider(long riderId, String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery_exception WHERE rider_id = ?");
        args.add(riderId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public List<DeliveryExceptionRecord> search(String status, String type, String severity, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery_exception WHERE 1 = 1");
        appendFilters(sql, args, status, type, severity);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public long countSearch(String status, String type, String severity) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery_exception WHERE 1 = 1");
        appendFilters(sql, args, status, type, severity);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public int updateStatus(long id, String status, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE delivery_exception SET status = ?, updated_at = ? WHERE id = ?",
                status, JdbcValues.timestamp(now), id);
    }

    public int updateHandled(long id, String status, String resolutionType, String resolutionNote,
                             boolean riderExempt, String handledBy, LocalDateTime handledAt) {
        return jdbcTemplate.update("""
                        UPDATE delivery_exception
                        SET status = ?, resolution_type = ?, resolution_note = ?, rider_exempt = ?,
                            handled_by = ?, handled_at = ?, updated_at = ?
                        WHERE id = ? AND status IN ('OPEN','PROCESSING')
                        """,
                status, resolutionType, resolutionNote, riderExempt ? 1 : 0, handledBy,
                JdbcValues.timestamp(handledAt), JdbcValues.timestamp(handledAt), id);
    }

    public int markRefundProcessing(
            long id,
            String resolutionNote,
            boolean riderExempt,
            String handledBy,
            LocalDateTime now
    ) {
        return jdbcTemplate.update("""
                        UPDATE delivery_exception
                        SET status = 'PROCESSING', resolution_type = 'REFUND', resolution_note = ?,
                            rider_exempt = ?, handled_by = ?, updated_at = ?
                        WHERE id = ? AND status = 'OPEN'
                        """,
                resolutionNote, riderExempt ? 1 : 0, handledBy, JdbcValues.timestamp(now), id);
    }

    public int updateRefundPending(long id, String resolutionNote, LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE delivery_exception
                        SET status = 'PROCESSING', resolution_type = 'REFUND', resolution_note = ?, updated_at = ?
                        WHERE id = ? AND status = 'PROCESSING' AND resolution_type = 'REFUND'
                        """,
                resolutionNote, JdbcValues.timestamp(now), id);
    }

    public List<DeliveryExceptionRecord> findPendingRefunds(int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_exception"
                        + " WHERE status = 'PROCESSING' AND resolution_type = 'REFUND'"
                        + " ORDER BY updated_at, id LIMIT ?",
                ROW_MAPPER, limit);
    }

    public List<DeliveryExceptionRecord> findExpiredHolds(LocalDateTime now, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_exception"
                        + " WHERE status = 'OPEN' AND hold_until_at IS NOT NULL AND hold_until_at <= ?"
                        + " ORDER BY hold_until_at LIMIT ?",
                ROW_MAPPER, JdbcValues.timestamp(now), limit);
    }

    public boolean hasExemptException(long taskId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_exception WHERE task_id = ? AND rider_exempt = 1",
                Long.class, taskId);
        return count != null && count > 0;
    }

    public int countAccessDeniedSince(String groupKey, LocalDateTime since) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM delivery_exception e
                        JOIN delivery_task t ON t.id = e.task_id
                        WHERE e.exception_type = 'ACCESS_DENIED' AND t.group_key = ? AND e.created_at >= ?
                        """,
                Integer.class, groupKey, JdbcValues.timestamp(since));
        return count == null ? 0 : count;
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, String status, String type, String severity) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND exception_type = ?");
            args.add(type.trim().toUpperCase());
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
            args.add(severity.trim().toUpperCase());
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.DECIMAL);
        } else {
            statement.setDouble(index, value);
        }
    }
}
