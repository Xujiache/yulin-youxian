package com.xianda.freshdelivery.delivery.tracking;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TrackingLocationDao {
    private static final String LATEST_COLUMNS =
            "rider_id, lat, lng, accuracy_meters, speed_mps, bearing, battery_level, motion_state,"
                    + " wave_id, current_task_id, located_at";

    private static final RowMapper<LatestRow> LATEST_MAPPER = (resultSet, rowNum) -> new LatestRow(
            resultSet.getLong("rider_id"),
            resultSet.getDouble("lat"),
            resultSet.getDouble("lng"),
            intOrNull(resultSet, "accuracy_meters"),
            doubleOrNull(resultSet, "speed_mps"),
            doubleOrNull(resultSet, "bearing"),
            intOrNull(resultSet, "battery_level"),
            resultSet.getString("motion_state"),
            longOrNull(resultSet, "wave_id"),
            longOrNull(resultSet, "current_task_id"),
            dateTime(resultSet, "located_at")
    );

    private static final RowMapper<HistoryPoint> HISTORY_MAPPER = (resultSet, rowNum) -> new HistoryPoint(
            resultSet.getDouble("lat"),
            resultSet.getDouble("lng"),
            doubleOrNull(resultSet, "speed_mps"),
            doubleOrNull(resultSet, "bearing"),
            resultSet.getString("motion_state"),
            dateTime(resultSet, "located_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public TrackingLocationDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertPoints(List<PointRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int[] results = jdbcTemplate.batchUpdate("""
                        INSERT IGNORE INTO rider_location
                            (rider_id, shift_id, wave_id, lat, lng, accuracy_meters, speed_mps, bearing, altitude,
                             provider, battery_level, network_type, motion_state, is_cleaned, located_at,
                             reported_at, batch_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        PointRow row = rows.get(index);
                        statement.setLong(1, row.riderId());
                        setNullableLong(statement, 2, row.shiftId());
                        setNullableLong(statement, 3, row.waveId());
                        statement.setDouble(4, row.lat());
                        statement.setDouble(5, row.lng());
                        setNullableInt(statement, 6, row.accuracyMeters());
                        setNullableDouble(statement, 7, row.speedMps());
                        setNullableDouble(statement, 8, row.bearing());
                        setNullableDouble(statement, 9, row.altitude());
                        statement.setString(10, row.provider());
                        setNullableInt(statement, 11, row.batteryLevel());
                        statement.setString(12, row.networkType());
                        statement.setString(13, row.motionState());
                        statement.setInt(14, row.cleaned() ? 1 : 0);
                        statement.setTimestamp(15, TrackingTimes.timestamp(row.locatedAt()));
                        statement.setTimestamp(16, TrackingTimes.timestamp(row.reportedAt()));
                        statement.setString(17, row.batchKey());
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });
        int inserted = 0;
        for (int result : results) {
            if (result > 0) {
                inserted += result;
            }
        }
        return inserted;
    }

    public boolean upsertLatest(LatestRow row) {
        return upsertLatest(row, null);
    }

    public boolean upsertLatest(LatestRow row, LocalDateTime maxTrustedLocatedAt) {
        String poisonRecovery = maxTrustedLocatedAt == null ? "" : " OR located_at > ?";
        List<Object> updateArguments = new ArrayList<>(13);
        updateArguments.add(row.lat());
        updateArguments.add(row.lng());
        updateArguments.add(row.accuracyMeters());
        updateArguments.add(row.speedMps());
        updateArguments.add(row.bearing());
        updateArguments.add(row.batteryLevel());
        updateArguments.add(row.motionState());
        updateArguments.add(row.waveId());
        updateArguments.add(row.currentTaskId());
        updateArguments.add(TrackingTimes.timestamp(row.locatedAt()));
        updateArguments.add(row.riderId());
        updateArguments.add(TrackingTimes.timestamp(row.locatedAt()));
        if (maxTrustedLocatedAt != null) {
            updateArguments.add(TrackingTimes.timestamp(maxTrustedLocatedAt));
        }
        String updateSql = """
                        UPDATE rider_location_latest
                        SET lat = ?, lng = ?, accuracy_meters = ?, speed_mps = ?, bearing = ?, battery_level = ?,
                            motion_state = ?, wave_id = ?, current_task_id = ?, located_at = ?
                        WHERE rider_id = ? AND (located_at < ?%s)
                        """.formatted(poisonRecovery);
        int updated = jdbcTemplate.update(updateSql, updateArguments.toArray());
        if (updated > 0) {
            return true;
        }
        int inserted = jdbcTemplate.update("""
                        INSERT IGNORE INTO rider_location_latest
                            (rider_id, lat, lng, accuracy_meters, speed_mps, bearing, battery_level,
                             motion_state, wave_id, current_task_id, located_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                row.riderId(),
                row.lat(),
                row.lng(),
                row.accuracyMeters(),
                row.speedMps(),
                row.bearing(),
                row.batteryLevel(),
                row.motionState(),
                row.waveId(),
                row.currentTaskId(),
                TrackingTimes.timestamp(row.locatedAt())
        );
        if (inserted > 0) {
            return true;
        }
        return jdbcTemplate.update(updateSql, updateArguments.toArray()) > 0;
    }

    public Optional<LatestRow> findLatest(long riderId) {
        return jdbcTemplate
                .query("SELECT " + LATEST_COLUMNS + " FROM rider_location_latest WHERE rider_id = ?",
                        LATEST_MAPPER, riderId)
                .stream()
                .findFirst();
    }

    public List<LatestRow> findAllLatest() {
        return jdbcTemplate.query("SELECT " + LATEST_COLUMNS + " FROM rider_location_latest", LATEST_MAPPER);
    }

    public Map<Long, LatestRow> findLatestOf(Collection<Long> riderIds) {
        Map<Long, LatestRow> result = new LinkedHashMap<>();
        List<Long> ids = riderIds == null
                ? List.of()
                : riderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<LatestRow> rows = jdbcTemplate.query(
                "SELECT " + LATEST_COLUMNS + " FROM rider_location_latest WHERE rider_id IN (" + placeholders + ")",
                LATEST_MAPPER,
                ids.toArray()
        );
        for (LatestRow row : rows) {
            result.put(row.riderId(), row);
        }
        return result;
    }

    public List<HistoryPoint> history(long riderId, LocalDateTime from, LocalDateTime to, int hardLimit) {
        return jdbcTemplate.query("""
                        SELECT lat, lng, speed_mps, bearing, motion_state, located_at
                        FROM rider_location
                        WHERE rider_id = ? AND located_at >= ? AND located_at <= ? AND is_cleaned = 1
                        ORDER BY located_at
                        LIMIT ?
                        """,
                HISTORY_MAPPER,
                riderId,
                TrackingTimes.timestamp(from),
                TrackingTimes.timestamp(to),
                hardLimit);
    }

    public int countReportedSince(LocalDateTime since) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_location WHERE reported_at >= ?",
                Integer.class,
                TrackingTimes.timestamp(since));
        return count == null ? 0 : count;
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Long.class);
        return count == null ? 0L : count;
    }

    public LocalDateTime oldestLocatedAt() {
        try {
            return jdbcTemplate.query(
                    "SELECT MIN(located_at) FROM rider_location",
                    resultSet -> resultSet.next() && resultSet.getTimestamp(1) != null
                            ? resultSet.getTimestamp(1).toLocalDateTime()
                            : null);
        } catch (DataAccessException exception) {
            return null;
        }
    }

    public int deleteLocationsBefore(LocalDateTime threshold, int batchSize) {
        return jdbcTemplate.update(
                "DELETE FROM rider_location WHERE located_at < ? LIMIT " + batchSize,
                TrackingTimes.timestamp(threshold));
    }

    public int deleteGeofenceEventsBefore(LocalDateTime threshold, int batchSize) {
        return jdbcTemplate.update(
                "DELETE FROM delivery_geofence_event WHERE occurred_at < ? LIMIT " + batchSize,
                TrackingTimes.timestamp(threshold));
    }

    public int deleteIneligibleLatest(LocalDateTime expiryThreshold) {
        return jdbcTemplate.update("""
                        DELETE FROM rider_location_latest
                        WHERE rider_location_latest.located_at < ?
                           OR NOT EXISTS (
                               SELECT 1
                               FROM rider r
                               WHERE r.id = rider_location_latest.rider_id
                                 AND r.deleted_at IS NULL
                                 AND r.account_status = 'ACTIVE'
                                 AND r.location_consent_at IS NOT NULL
                                 AND r.work_status IN ('ON_DUTY', 'BUSY', 'RESTING')
                                 AND EXISTS (
                                     SELECT 1
                                     FROM rider_shift s
                                     WHERE s.rider_id = r.id AND s.off_duty_at IS NULL
                                 )
                           )
                        """,
                TrackingTimes.timestamp(expiryThreshold));
    }

    public int deleteLatest(long riderId) {
        return jdbcTemplate.update("DELETE FROM rider_location_latest WHERE rider_id = ?", riderId);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
            return;
        }
        statement.setLong(index, value);
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
            return;
        }
        statement.setInt(index, value);
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DECIMAL);
            return;
        }
        statement.setDouble(index, value);
    }

    private static Integer intOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private static LocalDateTime dateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null ? null : resultSet.getTimestamp(column).toLocalDateTime();
    }

    public record PointRow(
            long riderId,
            Long shiftId,
            Long waveId,
            double lat,
            double lng,
            Integer accuracyMeters,
            Double speedMps,
            Double bearing,
            Double altitude,
            String provider,
            Integer batteryLevel,
            String networkType,
            String motionState,
            boolean cleaned,
            LocalDateTime locatedAt,
            LocalDateTime reportedAt,
            String batchKey
    ) {
    }

    public record LatestRow(
            Long riderId,
            Double lat,
            Double lng,
            Integer accuracyMeters,
            Double speedMps,
            Double bearing,
            Integer batteryLevel,
            String motionState,
            Long waveId,
            Long currentTaskId,
            LocalDateTime locatedAt
    ) {
    }

    public record HistoryPoint(
            Double lat,
            Double lng,
            Double speedMps,
            Double bearing,
            String motionState,
            LocalDateTime locatedAt
    ) {
    }

    public static List<HistoryPoint> downsample(List<HistoryPoint> points, int maxPoints) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        if (points.size() <= maxPoints || maxPoints <= 2) {
            return points;
        }
        List<HistoryPoint> sampled = new ArrayList<>(maxPoints);
        double step = (double) (points.size() - 1) / (maxPoints - 1);
        for (int index = 0; index < maxPoints - 1; index++) {
            sampled.add(points.get((int) Math.round(index * step)));
        }
        sampled.add(points.get(points.size() - 1));
        return sampled;
    }
}
