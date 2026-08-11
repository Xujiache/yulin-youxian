package com.xianda.freshdelivery.delivery.repository;

import com.xianda.freshdelivery.delivery.domain.RiderShift;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RiderShiftDao {
    private static final String[] GENERATED_ID = {"id"};
    private static final String COLUMNS = """
            id, rider_id, shift_date, on_duty_at, off_duty_at, off_duty_reason, online_seconds,
            continuous_seconds, rest_total_seconds, last_rest_at, fatigue_4h_notified_at,
            fatigue_8h_confirmed_at, dispatch_paused_until, task_count, delivered_count, on_time_count,
            exception_count, mileage_meters, earning_amount, helmet_confirmed, created_at, updated_at
            """;

    private static final RowMapper<RiderShift> ROW_MAPPER = (resultSet, rowNum) -> new RiderShift(
            resultSet.getLong("id"),
            resultSet.getLong("rider_id"),
            JdbcValues.date(resultSet, "shift_date"),
            JdbcValues.dateTime(resultSet, "on_duty_at"),
            JdbcValues.dateTime(resultSet, "off_duty_at"),
            resultSet.getString("off_duty_reason"),
            resultSet.getInt("online_seconds"),
            resultSet.getInt("continuous_seconds"),
            resultSet.getInt("rest_total_seconds"),
            JdbcValues.dateTime(resultSet, "last_rest_at"),
            JdbcValues.dateTime(resultSet, "fatigue_4h_notified_at"),
            JdbcValues.dateTime(resultSet, "fatigue_8h_confirmed_at"),
            JdbcValues.dateTime(resultSet, "dispatch_paused_until"),
            resultSet.getInt("task_count"),
            resultSet.getInt("delivered_count"),
            resultSet.getInt("on_time_count"),
            resultSet.getInt("exception_count"),
            resultSet.getInt("mileage_meters"),
            resultSet.getInt("earning_amount"),
            resultSet.getBoolean("helmet_confirmed"),
            JdbcValues.dateTime(resultSet, "created_at"),
            JdbcValues.dateTime(resultSet, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderShiftDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RiderShift> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rider_shift WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public Optional<RiderShift> findOpenShift(long riderId) {
        return jdbcTemplate.query("""
                                SELECT %s FROM rider_shift
                                WHERE rider_id = ? AND off_duty_at IS NULL
                                ORDER BY id DESC LIMIT 1
                                """.formatted(COLUMNS),
                        ROW_MAPPER, riderId)
                .stream()
                .findFirst();
    }

    public List<RiderShift> findOpenShifts() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rider_shift WHERE off_duty_at IS NULL ORDER BY id",
                ROW_MAPPER);
    }

    public List<RiderShift> findByRiderBetween(long riderId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                        SELECT %s FROM rider_shift
                        WHERE rider_id = ? AND shift_date >= ? AND shift_date <= ?
                        ORDER BY shift_date DESC, id DESC
                        """.formatted(COLUMNS),
                ROW_MAPPER, riderId, JdbcValues.sqlDate(from), JdbcValues.sqlDate(to));
    }

    public List<RiderShift> findByRiderAndDate(long riderId, LocalDate shiftDate) {
        return jdbcTemplate.query("""
                        SELECT %s FROM rider_shift
                        WHERE rider_id = ? AND shift_date = ?
                        ORDER BY id
                        """.formatted(COLUMNS),
                ROW_MAPPER, riderId, JdbcValues.sqlDate(shiftDate));
    }

    public ShiftAggregate aggregate(long riderId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                                SELECT
                                    COALESCE(SUM(delivered_count), 0) AS delivered_count,
                                    COALESCE(SUM(on_time_count), 0) AS on_time_count,
                                    COALESCE(SUM(online_seconds), 0) AS online_seconds
                                FROM rider_shift
                                WHERE rider_id = ? AND shift_date >= ? AND shift_date <= ?
                                """,
                        (resultSet, rowNum) -> new ShiftAggregate(
                                resultSet.getInt("delivered_count"),
                                resultSet.getInt("on_time_count"),
                                resultSet.getInt("online_seconds")),
                        riderId, JdbcValues.sqlDate(from), JdbcValues.sqlDate(to))
                .stream()
                .findFirst()
                .orElse(new ShiftAggregate(0, 0, 0));
    }

    public int sumClosedWorkedSeconds(long riderId, LocalDate shiftDate) {
        Integer value = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(online_seconds - rest_total_seconds), 0)
                        FROM rider_shift
                        WHERE rider_id = ? AND shift_date = ? AND off_duty_at IS NOT NULL
                        """,
                Integer.class, riderId, JdbcValues.sqlDate(shiftDate));
        return value == null ? 0 : value;
    }

    public long insert(RiderShift shift) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO rider_shift
                        (rider_id, shift_date, on_duty_at, online_seconds, continuous_seconds,
                         rest_total_seconds, helmet_confirmed)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, GENERATED_ID);
            statement.setLong(1, shift.riderId());
            statement.setDate(2, JdbcValues.sqlDate(shift.shiftDate()));
            statement.setTimestamp(3, JdbcValues.timestamp(shift.onDutyAt()));
            statement.setInt(4, shift.onlineSeconds() == null ? 0 : shift.onlineSeconds());
            statement.setInt(5, shift.continuousSeconds() == null ? 0 : shift.continuousSeconds());
            statement.setInt(6, shift.restTotalSeconds() == null ? 0 : shift.restTotalSeconds());
            statement.setBoolean(7, Boolean.TRUE.equals(shift.helmetConfirmed()));
            return statement;
        }, keyHolder);
        return JdbcValues.generatedId(keyHolder);
    }

    public int update(RiderShift shift) {
        return jdbcTemplate.update("""
                        UPDATE rider_shift SET
                            off_duty_at = ?, off_duty_reason = ?, online_seconds = ?, continuous_seconds = ?,
                            rest_total_seconds = ?, last_rest_at = ?, fatigue_4h_notified_at = ?,
                            fatigue_8h_confirmed_at = ?, dispatch_paused_until = ?, helmet_confirmed = ?,
                            updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ?
                        """,
                JdbcValues.timestamp(shift.offDutyAt()),
                shift.offDutyReason(),
                shift.onlineSeconds() == null ? 0 : shift.onlineSeconds(),
                shift.continuousSeconds() == null ? 0 : shift.continuousSeconds(),
                shift.restTotalSeconds() == null ? 0 : shift.restTotalSeconds(),
                JdbcValues.timestamp(shift.lastRestAt()),
                JdbcValues.timestamp(shift.fatigue4hNotifiedAt()),
                JdbcValues.timestamp(shift.fatigue8hConfirmedAt()),
                JdbcValues.timestamp(shift.dispatchPausedUntil()),
                Boolean.TRUE.equals(shift.helmetConfirmed()),
                shift.id());
    }

    public int updateStats(
            long shiftId,
            int taskCount,
            int deliveredCount,
            int onTimeCount,
            int exceptionCount,
            int mileageMeters,
            int earningAmount
    ) {
        return jdbcTemplate.update("""
                        UPDATE rider_shift SET
                            task_count = ?, delivered_count = ?, on_time_count = ?, exception_count = ?,
                            mileage_meters = ?, earning_amount = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ?
                        """,
                taskCount, deliveredCount, onTimeCount, exceptionCount, mileageMeters, earningAmount, shiftId);
    }

    public record ShiftAggregate(int deliveredCount, int onTimeCount, int onlineSeconds) {
    }
}
