package com.xianda.freshdelivery.delivery.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TrackingTaskDao {
    private static final RowMapper<WxTaskRow> WX_TASK_MAPPER = (resultSet, rowNum) -> new WxTaskRow(
            resultSet.getLong("id"),
            resultSet.getString("task_no"),
            resultSet.getLong("order_id"),
            longOrNull(resultSet, "wave_id"),
            longOrNull(resultSet, "rider_id"),
            resultSet.getString("status"),
            doubleOrNull(resultSet, "address_lat"),
            doubleOrNull(resultSet, "address_lng"),
            resultSet.getString("delivery_instruction"),
            dateTime(resultSet, "promised_at"),
            dateTime(resultSet, "eta_at"),
            dateTime(resultSet, "eta_lower_at"),
            dateTime(resultSet, "eta_upper_at"),
            dateTime(resultSet, "assigned_at"),
            dateTime(resultSet, "picked_up_at"),
            dateTime(resultSet, "departed_at"),
            dateTime(resultSet, "arrived_at"),
            dateTime(resultSet, "delivered_at"),
            longOrNull(resultSet, "current_exception_id")
    );

    private final JdbcTemplate jdbcTemplate;

    public TrackingTaskDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<WxTaskRow> findByOrderId(long orderId) {
        return jdbcTemplate.query("""
                                SELECT id, task_no, order_id, wave_id, rider_id, status, address_lat, address_lng,
                                       delivery_instruction, promised_at, eta_at, eta_lower_at, eta_upper_at,
                                       assigned_at, picked_up_at, departed_at, arrived_at, delivered_at,
                                       current_exception_id
                                FROM delivery_task
                                WHERE order_id = ?
                                """,
                        WX_TASK_MAPPER, orderId)
                .stream()
                .findFirst();
    }

    public int stopsAhead(long waveId, long taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM delivery_wave_stop s
                        JOIN delivery_task t ON t.id = s.task_id
                        WHERE s.wave_id = ?
                          AND s.seq_no < COALESCE(
                              (SELECT self.seq_no FROM delivery_wave_stop self
                                WHERE self.wave_id = ? AND self.task_id = ?), 0)
                          AND t.status NOT IN ('DELIVERED', 'RETURNED', 'CANCELLED')
                        """,
                Integer.class, waveId, waveId, taskId);
        return count == null ? 0 : count;
    }

    public List<GeofenceTaskRow> deliveringTasks(long riderId) {
        return jdbcTemplate.query("""
                        SELECT id, task_no, wave_id, address_lat, address_lng
                        FROM delivery_task
                        WHERE rider_id = ? AND status = 'DELIVERING'
                          AND address_lat IS NOT NULL AND address_lng IS NOT NULL
                        """,
                (resultSet, rowNum) -> new GeofenceTaskRow(
                        resultSet.getLong("id"),
                        resultSet.getString("task_no"),
                        longOrNull(resultSet, "wave_id"),
                        resultSet.getDouble("address_lat"),
                        resultSet.getDouble("address_lng")),
                riderId);
    }

    public int countByRiderAndStatus(long riderId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE rider_id = ? AND status = ?",
                Integer.class, riderId, status);
        return count == null ? 0 : count;
    }

    public Optional<CurrentAssignmentRow> currentAssignment(long riderId) {
        return jdbcTemplate.query("""
                                SELECT t.id, t.wave_id
                                FROM delivery_task t
                                LEFT JOIN delivery_wave_stop s
                                  ON s.wave_id = t.wave_id AND s.task_id = t.id
                                WHERE t.rider_id = ?
                                  AND t.status IN ('ASSIGNED', 'ACCEPTED', 'PICKED_UP', 'DELIVERING',
                                                   'ARRIVED', 'EXCEPTION')
                                ORDER BY CASE t.status
                                           WHEN 'ARRIVED' THEN 0
                                           WHEN 'DELIVERING' THEN 1
                                           WHEN 'PICKED_UP' THEN 2
                                           WHEN 'ACCEPTED' THEN 3
                                           WHEN 'ASSIGNED' THEN 4
                                           ELSE 5
                                         END,
                                         COALESCE(s.seq_no, 2147483647),
                                         t.id
                                LIMIT 1
                                """,
                        (resultSet, rowNum) -> new CurrentAssignmentRow(
                                resultSet.getLong("id"),
                                longOrNull(resultSet, "wave_id")),
                        riderId)
                .stream()
                .findFirst();
    }

    public long taskVersion(long riderId) {
        LocalDateTime maxUpdatedAt = jdbcTemplate.query(
                "SELECT MAX(updated_at) FROM delivery_task WHERE rider_id = ?",
                resultSet -> resultSet.next() && resultSet.getTimestamp(1) != null
                        ? resultSet.getTimestamp(1).toLocalDateTime()
                        : null,
                riderId);
        if (maxUpdatedAt == null) {
            return 0L;
        }
        return maxUpdatedAt.atZone(TrackingTimes.STORE_ZONE).toInstant().toEpochMilli();
    }

    public int updateInstruction(long taskId, String instruction) {
        return jdbcTemplate.update(
                "UPDATE delivery_task SET delivery_instruction = ? WHERE id = ?",
                instruction, taskId);
    }

    public void insertEvent(
            long taskId,
            String taskNo,
            Long waveId,
            String eventType,
            String operatorType,
            Long operatorId,
            String reason,
            String detailJson,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_task_event
                            (task_id, task_no, wave_id, event_type, operator_type, operator_id, reason,
                             detail_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                taskId, taskNo, waveId, eventType, operatorType, operatorId, reason, detailJson,
                TrackingTimes.timestamp(createdAt));
    }

    public Optional<RatingRow> findRating(long taskId) {
        return jdbcTemplate.query("""
                                SELECT star, tags, comment, created_at
                                FROM delivery_rating
                                WHERE task_id = ?
                                """,
                        (resultSet, rowNum) -> new RatingRow(
                                resultSet.getInt("star"),
                                resultSet.getString("tags"),
                                resultSet.getString("comment"),
                                dateTime(resultSet, "created_at")),
                        taskId)
                .stream()
                .findFirst();
    }

    public boolean ratingExists(long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_rating WHERE task_id = ?", Integer.class, taskId);
        return count != null && count > 0;
    }

    public void insertRating(
            long taskId,
            long orderId,
            Long riderId,
            long userId,
            int star,
            String tags,
            String comment,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_rating
                            (task_id, order_id, rider_id, user_id, star, tags, comment, is_negative, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                taskId, orderId, riderId == null ? 0L : riderId, userId, star, tags, comment,
                star <= 3 ? 1 : 0, TrackingTimes.timestamp(createdAt));
    }

    public Optional<String> taskNo(long taskId) {
        try {
            return Optional.ofNullable(jdbcTemplate.query(
                    "SELECT task_no FROM delivery_task WHERE id = ?",
                    resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                    taskId));
        } catch (DataAccessException exception) {
            return Optional.empty();
        }
    }

    private static LocalDateTime dateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null ? null : resultSet.getTimestamp(column).toLocalDateTime();
    }

    private static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    public record WxTaskRow(
            Long id,
            String taskNo,
            Long orderId,
            Long waveId,
            Long riderId,
            String status,
            Double addressLat,
            Double addressLng,
            String deliveryInstruction,
            LocalDateTime promisedAt,
            LocalDateTime etaAt,
            LocalDateTime etaLowerAt,
            LocalDateTime etaUpperAt,
            LocalDateTime assignedAt,
            LocalDateTime pickedUpAt,
            LocalDateTime departedAt,
            LocalDateTime arrivedAt,
            LocalDateTime deliveredAt,
            Long currentExceptionId
    ) {
    }

    public record RatingRow(
            int star,
            String tags,
            String comment,
            LocalDateTime createdAt
    ) {
    }

    public record GeofenceTaskRow(
            Long taskId,
            String taskNo,
            Long waveId,
            Double lat,
            Double lng
    ) {
    }

    public record CurrentAssignmentRow(Long taskId, Long waveId) {
    }
}
