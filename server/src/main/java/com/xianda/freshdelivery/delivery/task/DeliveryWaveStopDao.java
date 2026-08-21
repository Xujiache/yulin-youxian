package com.xianda.freshdelivery.delivery.task;

import static com.xianda.freshdelivery.delivery.task.TaskRowReader.booleanValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.dateTimeValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.doubleValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.intValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.longValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.timestamp;

import com.xianda.freshdelivery.delivery.domain.DeliveryWaveStop;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryWaveStopDao {
    private static final String COLUMNS = """
            id, wave_id, task_id, seq_no, original_seq_no, lat, lng, leg_distance_meters,
            leg_duration_seconds, handoff_estimate_seconds, plan_arrive_at, plan_depart_at,
            actual_arrive_at, actual_depart_at, adjusted_by_rider, created_at, updated_at
            """;

    private static final RowMapper<DeliveryWaveStop> MAPPER = (rs, rowNum) -> new DeliveryWaveStop(
            longValue(rs, "id"),
            longValue(rs, "wave_id"),
            longValue(rs, "task_id"),
            intValue(rs, "seq_no"),
            intValue(rs, "original_seq_no"),
            doubleValue(rs, "lat"),
            doubleValue(rs, "lng"),
            intValue(rs, "leg_distance_meters"),
            intValue(rs, "leg_duration_seconds"),
            intValue(rs, "handoff_estimate_seconds"),
            dateTimeValue(rs, "plan_arrive_at"),
            dateTimeValue(rs, "plan_depart_at"),
            dateTimeValue(rs, "actual_arrive_at"),
            dateTimeValue(rs, "actual_depart_at"),
            booleanValue(rs, "adjusted_by_rider"),
            dateTimeValue(rs, "created_at"),
            dateTimeValue(rs, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public DeliveryWaveStopDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DeliveryWaveStop> findByWaveId(long waveId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_wave_stop WHERE wave_id = ? ORDER BY seq_no",
                MAPPER,
                waveId
        );
    }

    public Optional<DeliveryWaveStop> findByWaveAndTask(long waveId, long taskId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_wave_stop WHERE wave_id = ? AND task_id = ?",
                MAPPER,
                waveId,
                taskId
        ).stream().findFirst();
    }

    public Optional<Integer> findSeqNo(long taskId) {
        return jdbcTemplate.query(
                "SELECT seq_no FROM delivery_wave_stop WHERE task_id = ? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getInt("seq_no"),
                taskId
        ).stream().findFirst();
    }

    public int maxSeqNo(long waveId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) FROM delivery_wave_stop WHERE wave_id = ?",
                Integer.class,
                waveId
        );
        return max == null ? 0 : max;
    }

    public void insert(long waveId, long taskId, int seqNo, Double lat, Double lng, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_wave_stop (
                    wave_id, task_id, seq_no, original_seq_no, lat, lng, leg_distance_meters,
                    leg_duration_seconds, handoff_estimate_seconds, adjusted_by_rider, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,0,0,0,0,?,?)
                """, waveId, taskId, seqNo, seqNo, lat, lng, timestamp(now), timestamp(now));
    }

    public void updateSequence(long waveId, long taskId, int seqNo, boolean adjustedByRider, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE delivery_wave_stop SET seq_no = ?, adjusted_by_rider = ?, updated_at = ?
                WHERE wave_id = ? AND task_id = ?
                """, seqNo, adjustedByRider ? 1 : 0, timestamp(now), waveId, taskId);
    }

    public void markActualArrive(long taskId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_wave_stop SET actual_arrive_at = COALESCE(actual_arrive_at, ?), updated_at = ? WHERE task_id = ?",
                timestamp(now), timestamp(now), taskId
        );
    }

    public void markActualDepart(long taskId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_wave_stop SET actual_depart_at = ?, updated_at = ? WHERE task_id = ?",
                timestamp(now), timestamp(now), taskId
        );
    }

    public void deleteByWaveAndTask(long waveId, long taskId) {
        jdbcTemplate.update("DELETE FROM delivery_wave_stop WHERE wave_id = ? AND task_id = ?", waveId, taskId);
    }
}
