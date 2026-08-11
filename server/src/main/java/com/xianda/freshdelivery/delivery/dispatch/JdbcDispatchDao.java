package com.xianda.freshdelivery.delivery.dispatch;

import com.xianda.freshdelivery.delivery.common.DeliveryErrorCode;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDispatchDao implements DispatchDao {
    private static final DateTimeFormatter WAVE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final String WAVE_NO_PREFIX = "BC";
    private static final int WAVE_NO_DATE_END = WAVE_NO_PREFIX.length() + 8;

    private static final String TASK_COLUMNS = "id, task_no, wave_id, rider_id, status, address_detail,"
            + " address_lat, address_lng, area_label, building_label, group_key, floor_no, room_no,"
            + " item_count, total_weight_kg, cold_chain_level, delivery_date, window_start_at, window_end_at,"
            + " promised_at, eta_at, extra_time_seconds, picked_ready_at, hold_until_at, reassign_count,"
            + " priority, handoff_seconds";

    private static final String RIDER_QUERY = "SELECT r.id, r.rider_no, r.name, r.account_status, r.work_status,"
            + " r.vehicle_type, r.max_concurrent_task, r.capacity_weight_kg, r.probation, r.service_score,"
            + " r.level_code, s.id AS shift_id, s.dispatch_paused_until, l.lat, l.lng, l.located_at,"
            + " l.wave_id AS current_wave_id"
            + " FROM rider r"
            + " LEFT JOIN rider_shift s ON s.id = (SELECT s2.id FROM rider_shift s2"
            + " WHERE s2.rider_id = r.id AND s2.off_duty_at IS NULL ORDER BY s2.id DESC LIMIT 1)"
            + " LEFT JOIN rider_location_latest l ON l.rider_id = r.id"
            + " WHERE r.deleted_at IS NULL";

    private static final List<String> ACTIVE_STATUSES =
            List.of("ASSIGNED", "ACCEPTED", "PICKED_UP", "DELIVERING", "ARRIVED");
    private static final List<String> REASSIGNABLE_STATUSES = List.of("ASSIGNED", "ACCEPTED");

    private final JdbcTemplate jdbcTemplate;

    public JdbcDispatchDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DispatchTaskRow> findPendingTasks() {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE status = 'PENDING'"
                + " ORDER BY priority DESC, picked_ready_at ASC, id ASC", taskMapper());
    }

    @Override
    public List<DispatchTaskRow> findTasksByIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(taskIds.size(), "?"));
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE id IN (" + placeholders + ")"
                + " ORDER BY id", taskMapper(), taskIds.toArray());
    }

    @Override
    public List<DispatchTaskRow> findTasksByWave(long waveId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE wave_id = ? ORDER BY id",
                taskMapper(), waveId);
    }

    @Override
    public Optional<DispatchTaskRow> findTask(long taskId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE id = ?", taskMapper(), taskId)
                .stream().findFirst();
    }

    @Override
    public List<DispatchTaskRow> lockPendingTasks(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = taskIds.stream().distinct().sorted().toList();
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task"
                        + " WHERE id IN (" + placeholders + ") AND status = 'PENDING'"
                        + " AND rider_id IS NULL AND wave_id IS NULL ORDER BY id FOR UPDATE",
                taskMapper(), ids.toArray());
    }

    @Override
    public Optional<DispatchTaskRow> lockTask(long taskId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE id = ? FOR UPDATE",
                taskMapper(), taskId).stream().findFirst();
    }

    @Override
    public boolean lockRiders(List<Long> riderIds) {
        if (riderIds == null || riderIds.isEmpty()) {
            return false;
        }
        List<Long> ids = riderIds.stream().distinct().sorted().toList();
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        List<Long> locked = jdbcTemplate.queryForList(
                "SELECT id FROM rider WHERE id IN (" + placeholders + ")"
                        + " AND deleted_at IS NULL ORDER BY id FOR UPDATE",
                Long.class, ids.toArray());
        return locked.size() == ids.size();
    }

    @Override
    public List<DispatchTaskRow> findActiveTasksByRider(long riderId) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE rider_id = ?"
                        + " AND status IN (" + placeholders(ACTIVE_STATUSES) + ") ORDER BY id",
                taskMapper(), argumentsWith(riderId, ACTIVE_STATUSES));
    }

    @Override
    public List<DispatchTaskRow> findReassignCandidates() {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM delivery_task WHERE rider_id IS NOT NULL"
                        + " AND status IN (" + placeholders(REASSIGNABLE_STATUSES) + ") ORDER BY id",
                taskMapper(), REASSIGNABLE_STATUSES.toArray());
    }

    @Override
    public List<RiderCandidateRow> findRiderPool() {
        return jdbcTemplate.query(RIDER_QUERY + " ORDER BY r.id", riderMapper());
    }

    @Override
    public Optional<RiderCandidateRow> findRider(long riderId) {
        return jdbcTemplate.query(RIDER_QUERY + " AND r.id = ?", riderMapper(), riderId).stream().findFirst();
    }

    @Override
    public Optional<DispatchWaveRow> findAppendableWave(long riderId) {
        return jdbcTemplate.query("SELECT id, wave_no, rider_id, status, delivery_date, task_count"
                        + " FROM delivery_wave WHERE rider_id = ? AND status IN ('PLANNING', 'ASSIGNED')"
                        + " ORDER BY id DESC LIMIT 1 FOR UPDATE",
                waveMapper(), riderId).stream().findFirst();
    }

    @Override
    public long createWave(Long riderId, LocalDate deliveryDate, LocalDateTime now) {
        LocalDate date = deliveryDate == null ? now.toLocalDate() : deliveryDate;
        String status = riderId == null ? "PLANNING" : "ASSIGNED";
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return insertWave(nextWaveNo(date), riderId, status, date, now);
            } catch (DuplicateKeyException ignored) {
                continue;
            }
        }
        throw new DeliveryException(DeliveryErrorCode.TASK_ALREADY_EXISTS, "波次号生成冲突，请重试");
    }

    private long insertWave(String waveNo, Long riderId, String status, LocalDate deliveryDate, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO delivery_wave (wave_no, rider_id, status, delivery_date, assigned_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", new String[]{"id"});
            statement.setString(1, waveNo);
            if (riderId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, riderId);
            }
            statement.setString(3, status);
            statement.setDate(4, java.sql.Date.valueOf(deliveryDate));
            statement.setTimestamp(5, riderId == null ? null : Timestamp.valueOf(now));
            statement.setTimestamp(6, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        Long key = JdbcValues.generatedIdOrNull(keyHolder);
        if (key == null) {
            throw new DeliveryException(DeliveryErrorCode.ROUTE_PLAN_FAILED, "波次创建失败，未返回主键");
        }
        return key;
    }

    private String nextWaveNo(LocalDate date) {
        String prefix = WAVE_NO_PREFIX + date.format(WAVE_DATE);
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(wave_no, ?) AS UNSIGNED)), 0) FROM delivery_wave"
                        + " WHERE wave_no LIKE ?",
                Long.class, WAVE_NO_DATE_END + 1, prefix + "%");
        long next = (max == null ? 0L : max) + 1L;
        return prefix + String.format(Locale.ROOT, "%04d", next);
    }

    @Override
    public boolean bindReassignedTaskToWave(long taskId, long riderId, long waveId, LocalDateTime now) {
        int changed = jdbcTemplate.update("UPDATE delivery_task SET wave_id = ?, updated_at = ?"
                        + " WHERE id = ? AND rider_id = ? AND wave_id IS NULL"
                        + " AND status IN ('ASSIGNED', 'ACCEPTED')",
                waveId, Timestamp.valueOf(now), taskId, riderId);
        if (changed == 0) {
            return false;
        }
        jdbcTemplate.update("DELETE FROM delivery_wave_stop WHERE task_id = ? AND wave_id <> ?", taskId, waveId);
        Integer stopCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_wave_stop WHERE wave_id = ? AND task_id = ?",
                Integer.class, waveId, taskId);
        if (stopCount != null && stopCount > 0) {
            return true;
        }
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) FROM delivery_wave_stop WHERE wave_id = ?",
                Integer.class, waveId);
        int seqNo = (maxSeq == null ? 0 : maxSeq) + 1;
        int inserted = jdbcTemplate.update("INSERT INTO delivery_wave_stop"
                        + " (wave_id, task_id, seq_no, original_seq_no, lat, lng)"
                        + " SELECT ?, id, ?, ?, address_lat, address_lng FROM delivery_task"
                        + " WHERE id = ? AND rider_id = ? AND wave_id = ?",
                waveId, seqNo, seqNo, taskId, riderId, waveId);
        return inserted == 1;
    }

    @Override
    public void refreshWaveAggregates(long waveId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE delivery_wave w SET"
                + " w.task_count = (SELECT COUNT(*) FROM delivery_task t WHERE t.wave_id = w.id),"
                + " w.completed_count = (SELECT COUNT(*) FROM delivery_task t WHERE t.wave_id = w.id"
                + "   AND t.status IN ('DELIVERED', 'RETURNED', 'CANCELLED')),"
                + " w.total_weight_kg = (SELECT COALESCE(SUM(t.total_weight_kg), 0) FROM delivery_task t"
                + "   WHERE t.wave_id = w.id),"
                + " w.total_item_count = (SELECT COALESCE(SUM(t.item_count), 0) FROM delivery_task t"
                + "   WHERE t.wave_id = w.id),"
                + " w.max_cold_chain_level = COALESCE((SELECT t.cold_chain_level FROM delivery_task t"
                + "   WHERE t.wave_id = w.id"
                + "   ORDER BY FIELD(t.cold_chain_level, 'NORMAL', 'CHILLED', 'FROZEN') DESC LIMIT 1), 'NORMAL'),"
                + " w.updated_at = ?"
                + " WHERE w.id = ?", Timestamp.valueOf(now), waveId);
    }

    @Override
    public boolean deleteWaveIfEmpty(long waveId) {
        jdbcTemplate.update("DELETE FROM delivery_wave_stop WHERE wave_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM delivery_task t WHERE t.wave_id = ?)",
                waveId, waveId);
        return jdbcTemplate.update("DELETE FROM delivery_wave WHERE id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM delivery_task t WHERE t.wave_id = ?)",
                waveId, waveId) == 1;
    }

    @Override
    public int countPendingTasks() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE status = 'PENDING'", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void insertMessage(Long riderId, String messageType, String title, String content,
                              String priority, boolean needVoice, String linkType, String linkTarget) {
        jdbcTemplate.update("INSERT INTO rider_message (rider_id, message_type, title, content, link_type,"
                        + " link_target, priority, need_voice, push_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                riderId, messageType, title, content, linkType, linkTarget, priority, needVoice ? 1 : 0);
    }

    private static String placeholders(List<String> values) {
        return String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
    }

    private static Object[] argumentsWith(Object first, List<String> rest) {
        Object[] arguments = new Object[rest.size() + 1];
        arguments[0] = first;
        for (int index = 0; index < rest.size(); index++) {
            arguments[index + 1] = rest.get(index);
        }
        return arguments;
    }

    private RowMapper<DispatchTaskRow> taskMapper() {
        return (rs, rowNum) -> new DispatchTaskRow(
                rs.getLong("id"),
                rs.getString("task_no"),
                nullableLong(rs, "wave_id"),
                nullableLong(rs, "rider_id"),
                rs.getString("status"),
                rs.getString("address_detail"),
                nullableDouble(rs, "address_lat"),
                nullableDouble(rs, "address_lng"),
                rs.getString("area_label"),
                rs.getString("building_label"),
                rs.getString("group_key"),
                nullableInt(rs, "floor_no"),
                rs.getString("room_no"),
                rs.getInt("item_count"),
                rs.getDouble("total_weight_kg"),
                rs.getString("cold_chain_level"),
                localDate(rs, "delivery_date"),
                dateTime(rs, "window_start_at"),
                dateTime(rs, "window_end_at"),
                dateTime(rs, "promised_at"),
                dateTime(rs, "eta_at"),
                rs.getInt("extra_time_seconds"),
                dateTime(rs, "picked_ready_at"),
                dateTime(rs, "hold_until_at"),
                rs.getInt("reassign_count"),
                rs.getInt("priority"),
                nullableInt(rs, "handoff_seconds"));
    }

    private RowMapper<RiderCandidateRow> riderMapper() {
        return (rs, rowNum) -> new RiderCandidateRow(
                rs.getLong("id"),
                rs.getString("rider_no"),
                rs.getString("name"),
                rs.getString("account_status"),
                rs.getString("work_status"),
                rs.getString("vehicle_type"),
                rs.getInt("max_concurrent_task"),
                rs.getDouble("capacity_weight_kg"),
                rs.getBoolean("probation"),
                rs.getInt("service_score"),
                rs.getString("level_code"),
                nullableLong(rs, "shift_id"),
                dateTime(rs, "dispatch_paused_until"),
                nullableDouble(rs, "lat"),
                nullableDouble(rs, "lng"),
                dateTime(rs, "located_at"),
                nullableLong(rs, "current_wave_id"));
    }

    private RowMapper<DispatchWaveRow> waveMapper() {
        return (rs, rowNum) -> new DispatchWaveRow(
                rs.getLong("id"),
                rs.getString("wave_no"),
                nullableLong(rs, "rider_id"),
                rs.getString("status"),
                localDate(rs, "delivery_date"),
                rs.getInt("task_count"));
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
