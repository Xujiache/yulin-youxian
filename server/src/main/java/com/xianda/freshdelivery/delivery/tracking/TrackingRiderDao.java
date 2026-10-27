package com.xianda.freshdelivery.delivery.tracking;

import com.xianda.freshdelivery.delivery.dto.RiderMessageDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TrackingRiderDao {
    private static final RowMapper<BoardRiderRow> BOARD_RIDER_MAPPER = (resultSet, rowNum) -> new BoardRiderRow(
            resultSet.getLong("id"),
            resultSet.getString("rider_no"),
            resultSet.getString("name"),
            resultSet.getString("avatar_url"),
            resultSet.getString("work_status"),
            resultSet.getInt("max_concurrent_task"),
            resultSet.getDouble("capacity_weight_kg"),
            resultSet.getInt("service_score"),
            resultSet.getInt("probation") != 0,
            dateTime(resultSet, "on_duty_at"),
            intOrZero(resultSet, "continuous_seconds"),
            dateTime(resultSet, "dispatch_paused_until"),
            intOrZero(resultSet, "delivered_count"),
            intOrZero(resultSet, "on_time_count"),
            dateTime(resultSet, "fatigue_4h_notified_at"),
            dateTime(resultSet, "fatigue_8h_confirmed_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public TrackingRiderDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IngestGuard> ingestGuard(long riderId) {
        return jdbcTemplate.query("""
                                SELECT r.location_consent_at,
                                       r.work_status,
                                       r.account_status,
                                       (SELECT s.id FROM rider_shift s
                                         WHERE s.rider_id = r.id AND s.off_duty_at IS NULL
                                         ORDER BY s.id DESC LIMIT 1) AS shift_id
                                FROM rider r
                                WHERE r.id = ? AND r.deleted_at IS NULL
                                """,
                        (resultSet, rowNum) -> new IngestGuard(
                                dateTime(resultSet, "location_consent_at"),
                                resultSet.getString("work_status"),
                                resultSet.getString("account_status"),
                                longOrNull(resultSet, "shift_id")),
                        riderId)
                .stream()
                .findFirst();
    }

    public Optional<TrackingRiderRow> trackingRider(long riderId) {
        return jdbcTemplate.query("""
                                SELECT r.id, r.name, r.avatar_url, r.vehicle_type, r.phone, r.service_score,
                                       (SELECT AVG(dr.star) FROM delivery_rating dr WHERE dr.rider_id = r.id)
                                           AS rating_star
                                FROM rider r
                                WHERE r.id = ?
                                """,
                        (resultSet, rowNum) -> new TrackingRiderRow(
                                resultSet.getLong("id"),
                                resultSet.getString("name"),
                                resultSet.getString("avatar_url"),
                                resultSet.getString("vehicle_type"),
                                resultSet.getString("phone"),
                                resultSet.getInt("service_score"),
                                doubleOrNull(resultSet, "rating_star")),
                        riderId)
                .stream()
                .findFirst();
    }

    public List<BoardRiderRow> onDutyRiders() {
        return jdbcTemplate.query("""
                SELECT r.id, r.rider_no, r.name, r.avatar_url, r.work_status, r.max_concurrent_task,
                       r.capacity_weight_kg, r.service_score, r.probation,
                       s.on_duty_at, s.continuous_seconds, s.dispatch_paused_until,
                       s.delivered_count, s.on_time_count, s.fatigue_4h_notified_at, s.fatigue_8h_confirmed_at
                FROM rider r
                LEFT JOIN rider_shift s ON s.rider_id = r.id AND s.off_duty_at IS NULL
                WHERE r.deleted_at IS NULL AND r.account_status = 'ACTIVE'
                  AND r.work_status IN ('ON_DUTY', 'BUSY', 'RESTING')
                ORDER BY r.id
                """, BOARD_RIDER_MAPPER);
    }

    public int unreadMessageCount(long riderId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM rider_message
                        WHERE (rider_id = ? OR rider_id IS NULL) AND read_at IS NULL
                        """,
                Integer.class, riderId);
        return count == null ? 0 : count;
    }

    public List<RiderMessageDto> urgentMessages(long riderId, int limit) {
        return jdbcTemplate.query("""
                        SELECT id, message_type, title, content, link_type, link_target, priority,
                               need_voice, need_ack, acked_at, read_at, created_at
                        FROM rider_message
                        WHERE (rider_id = ? OR rider_id IS NULL) AND read_at IS NULL AND need_voice = 1
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                (resultSet, rowNum) -> new RiderMessageDto(
                        resultSet.getLong("id"),
                        resultSet.getString("message_type"),
                        resultSet.getString("title"),
                        resultSet.getString("content"),
                        resultSet.getString("link_type"),
                        resultSet.getString("link_target"),
                        resultSet.getString("priority"),
                        resultSet.getInt("need_voice") != 0,
                        resultSet.getInt("need_ack") != 0,
                        TrackingTimes.format(dateTime(resultSet, "acked_at")),
                        TrackingTimes.format(dateTime(resultSet, "read_at")),
                        TrackingTimes.format(dateTime(resultSet, "created_at"))),
                riderId, limit);
    }

    public Optional<ShiftRow> openShift(long riderId) {
        return jdbcTemplate.query("""
                                SELECT id, on_duty_at, continuous_seconds, online_seconds, rest_total_seconds,
                                       dispatch_paused_until, fatigue_4h_notified_at, fatigue_8h_confirmed_at
                                FROM rider_shift
                                WHERE rider_id = ? AND off_duty_at IS NULL
                                ORDER BY id DESC LIMIT 1
                                """,
                        (resultSet, rowNum) -> new ShiftRow(
                                resultSet.getLong("id"),
                                dateTime(resultSet, "on_duty_at"),
                                resultSet.getInt("continuous_seconds"),
                                resultSet.getInt("online_seconds"),
                                resultSet.getInt("rest_total_seconds"),
                                dateTime(resultSet, "dispatch_paused_until"),
                                dateTime(resultSet, "fatigue_4h_notified_at"),
                                dateTime(resultSet, "fatigue_8h_confirmed_at")),
                        riderId)
                .stream()
                .findFirst();
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

    private static int intOrZero(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? 0 : value;
    }

    public record IngestGuard(
            LocalDateTime locationConsentAt,
            String workStatus,
            String accountStatus,
            Long shiftId
    ) {
    }

    public record TrackingRiderRow(
            Long riderId,
            String name,
            String avatarUrl,
            String vehicleType,
            String phone,
            Integer serviceScore,
            Double ratingStar
    ) {
    }

    public record BoardRiderRow(
            Long riderId,
            String riderNo,
            String name,
            String avatarUrl,
            String workStatus,
            Integer maxConcurrentTask,
            Double capacityWeightKg,
            Integer serviceScore,
            Boolean probation,
            LocalDateTime onDutyAt,
            Integer continuousSeconds,
            LocalDateTime dispatchPausedUntil,
            Integer deliveredCount,
            Integer onTimeCount,
            LocalDateTime fatigue4hNotifiedAt,
            LocalDateTime fatigue8hConfirmedAt
    ) {
    }

    public record ShiftRow(
            Long shiftId,
            LocalDateTime onDutyAt,
            Integer continuousSeconds,
            Integer onlineSeconds,
            Integer restTotalSeconds,
            LocalDateTime dispatchPausedUntil,
            LocalDateTime fatigue4hNotifiedAt,
            LocalDateTime fatigue8hConfirmedAt
    ) {
    }
}
