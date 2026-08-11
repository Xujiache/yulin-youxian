package com.xianda.freshdelivery.delivery.task;

import static com.xianda.freshdelivery.delivery.task.TaskRowReader.date;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.dateTimeValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.dateValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.decimalValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.intValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.longValue;
import static com.xianda.freshdelivery.delivery.task.TaskRowReader.timestamp;

import com.xianda.freshdelivery.delivery.domain.DeliveryWave;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryWaveDao {
    private static final String COLUMNS = """
            id, wave_no, rider_id, status, delivery_date, task_count, completed_count,
            total_weight_kg, total_item_count, max_cold_chain_level, plan_distance_meters,
            plan_duration_seconds, actual_distance_meters, plan_return_at, route_plan_id,
            optimizer_name, matrix_provider, assigned_at, started_at, completed_at, created_at, updated_at
            """;

    private static final RowMapper<DeliveryWave> MAPPER = (rs, rowNum) -> new DeliveryWave(
            longValue(rs, "id"),
            rs.getString("wave_no"),
            longValue(rs, "rider_id"),
            rs.getString("status"),
            dateValue(rs, "delivery_date"),
            intValue(rs, "task_count"),
            intValue(rs, "completed_count"),
            decimalValue(rs, "total_weight_kg"),
            intValue(rs, "total_item_count"),
            rs.getString("max_cold_chain_level"),
            intValue(rs, "plan_distance_meters"),
            intValue(rs, "plan_duration_seconds"),
            intValue(rs, "actual_distance_meters"),
            dateTimeValue(rs, "plan_return_at"),
            longValue(rs, "route_plan_id"),
            rs.getString("optimizer_name"),
            rs.getString("matrix_provider"),
            dateTimeValue(rs, "assigned_at"),
            dateTimeValue(rs, "started_at"),
            dateTimeValue(rs, "completed_at"),
            dateTimeValue(rs, "created_at"),
            dateTimeValue(rs, "updated_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public DeliveryWaveDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DeliveryWave> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM delivery_wave WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<DeliveryWave> findByIdForUpdate(long id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM delivery_wave WHERE id = ? FOR UPDATE", MAPPER, id
        ).stream().findFirst();
    }

    public List<DeliveryWave> search(LocalDate deliveryDate, String status, Long riderId, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery_wave WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (deliveryDate != null) {
            sql.append(" AND delivery_date = ?");
            args.add(date(deliveryDate));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (riderId != null) {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(LocalDate deliveryDate, String status, Long riderId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery_wave WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (deliveryDate != null) {
            sql.append(" AND delivery_date = ?");
            args.add(date(deliveryDate));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (riderId != null) {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    public String maxWaveNoWithPrefix(String prefix) {
        return jdbcTemplate.query(
                "SELECT MAX(wave_no) FROM delivery_wave WHERE wave_no LIKE ?",
                rs -> rs.next() ? rs.getString(1) : null,
                prefix + "%"
        );
    }

    public long insert(String waveNo, Long riderId, String status, LocalDate deliveryDate, LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO delivery_wave (
                    wave_no, rider_id, status, delivery_date, task_count, completed_count,
                    total_weight_kg, total_item_count, max_cold_chain_level, plan_distance_meters,
                    plan_duration_seconds, actual_distance_meters, assigned_at, created_at, updated_at
                ) VALUES (?,?,?,?,0,0,0,0,'NORMAL',0,0,0,?,?,?)
                """,
                waveNo,
                riderId,
                status,
                date(deliveryDate),
                riderId == null ? null : timestamp(now),
                timestamp(now),
                timestamp(now)
        );
        Long id = jdbcTemplate.queryForObject("SELECT id FROM delivery_wave WHERE wave_no = ?", Long.class, waveNo);
        return id == null ? 0L : id;
    }

    public void updateStatus(long waveId, String status, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_wave SET status = ?, updated_at = ? WHERE id = ?",
                status, timestamp(now), waveId
        );
    }

    public void markStarted(long waveId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_wave SET status = 'DELIVERING', started_at = COALESCE(started_at, ?), updated_at = ? WHERE id = ?",
                timestamp(now), timestamp(now), waveId
        );
    }

    public void markCompleted(long waveId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_wave SET status = 'COMPLETED', completed_at = ?, updated_at = ? WHERE id = ?",
                timestamp(now), timestamp(now), waveId
        );
    }

    public void updateRider(long waveId, Long riderId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE delivery_wave SET rider_id = ?, assigned_at = ?, updated_at = ? WHERE id = ?",
                riderId, timestamp(now), timestamp(now), waveId
        );
    }

    public void refreshAggregates(long waveId, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE delivery_wave SET
                    task_count = (SELECT COUNT(*) FROM delivery_task WHERE wave_id = ?),
                    completed_count = (SELECT COUNT(*) FROM delivery_task WHERE wave_id = ?
                        AND status IN ('DELIVERED','RETURNED','CANCELLED')),
                    total_weight_kg = COALESCE((SELECT SUM(total_weight_kg) FROM delivery_task WHERE wave_id = ?), 0),
                    total_item_count = COALESCE((SELECT SUM(item_count) FROM delivery_task WHERE wave_id = ?), 0),
                    updated_at = ?
                WHERE id = ?
                """, waveId, waveId, waveId, waveId, timestamp(now), waveId);
        jdbcTemplate.update("""
                UPDATE delivery_wave SET max_cold_chain_level = COALESCE((
                    SELECT CASE
                        WHEN SUM(CASE WHEN cold_chain_level = 'FROZEN' THEN 1 ELSE 0 END) > 0 THEN 'FROZEN'
                        WHEN SUM(CASE WHEN cold_chain_level = 'CHILLED' THEN 1 ELSE 0 END) > 0 THEN 'CHILLED'
                        ELSE 'NORMAL' END
                    FROM delivery_task WHERE wave_id = ?), 'NORMAL')
                WHERE id = ?
                """, waveId, waveId);
    }

    public BigDecimal totalWeight(long waveId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_weight_kg), 0) FROM delivery_task WHERE wave_id = ?",
                BigDecimal.class,
                waveId
        );
    }
}
