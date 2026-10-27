package com.xianda.freshdelivery.delivery.settlement;

import com.xianda.freshdelivery.delivery.repository.JdbcValues;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementTaskQueryDao {
    private static final RowMapper<TaskEarningRow> ROW_MAPPER = (resultSet, rowNum) -> new TaskEarningRow(
            resultSet.getLong("id"),
            resultSet.getString("task_no"),
            JdbcValues.longOrNull(resultSet, "rider_id"),
            resultSet.getString("status"),
            resultSet.getString("group_key"),
            JdbcValues.intOrNull(resultSet, "floor_no"),
            JdbcValues.decimal(resultSet, "total_weight_kg"),
            resultSet.getInt("actual_distance_meters"),
            resultSet.getInt("plan_distance_meters"),
            JdbcValues.dateTime(resultSet, "delivered_at"),
            JdbcValues.dateTime(resultSet, "created_at"),
            hasElevator(resultSet)
    );

    private final JdbcTemplate jdbcTemplate;

    public SettlementTaskQueryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TaskEarningRow> findEarningRow(long taskId) {
        return findEarningRow(taskId, false);
    }

    public Optional<TaskEarningRow> findEarningRowForUpdate(long taskId) {
        return findEarningRow(taskId, true);
    }

    private Optional<TaskEarningRow> findEarningRow(long taskId, boolean forUpdate) {
        return jdbcTemplate.query("""
                                SELECT t.id, t.task_no, t.rider_id, t.status, t.group_key, t.floor_no,
                                       t.total_weight_kg, t.actual_distance_meters, t.plan_distance_meters,
                                       t.delivered_at, t.created_at,
                                       (SELECT MAX(b.has_elevator) FROM building_handoff_stat b
                                         WHERE b.group_key = t.group_key) AS has_elevator
                                FROM delivery_task t WHERE t.id = ?%s
                                """.formatted(forUpdate ? " FOR UPDATE" : ""),
                        ROW_MAPPER, taskId)
                .stream()
                .findFirst();
    }

    public List<Long> findDeliveredWithoutEarning(LocalDateTime from, LocalDateTime to, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT t.id FROM delivery_task t
                        WHERE t.status = 'DELIVERED' AND t.rider_id IS NOT NULL AND t.delivered_at IS NOT NULL
                          AND t.delivered_at >= ? AND t.delivered_at < ?
                          AND NOT EXISTS (
                              SELECT 1 FROM delivery_task_event e
                              WHERE e.task_id = t.id AND e.event_type = 'EARNING_SETTLED'
                          )
                        ORDER BY t.delivered_at LIMIT ?
                        """,
                Long.class, JdbcValues.timestamp(from), JdbcValues.timestamp(to), limit);
    }

    public boolean earningCompleted(long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task_event"
                        + " WHERE task_id = ? AND event_type = 'EARNING_SETTLED'",
                Integer.class, taskId);
        return count != null && count > 0;
    }

    public void markEarningCompleted(TaskEarningRow task, LocalDateTime now) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_task_event
                            (task_id, task_no, event_type, from_status, to_status,
                             operator_type, operator_name, reason, created_at)
                        VALUES (?, ?, 'EARNING_SETTLED', 'DELIVERED', 'DELIVERED',
                                'SYSTEM', '系统', '收入明细完整写入', ?)
                        """,
                task.taskId(), task.taskNo(), JdbcValues.timestamp(now));
    }

    public int consecutiveOnTimeStreak(long riderId, int lookback) {
        List<Integer> flags = jdbcTemplate.queryForList("""
                        SELECT is_on_time FROM delivery_task
                        WHERE rider_id = ? AND status = 'DELIVERED' AND is_on_time IS NOT NULL
                        ORDER BY delivered_at DESC, id DESC LIMIT ?
                        """,
                Integer.class, riderId, lookback);
        int streak = 0;
        for (Integer flag : flags) {
            if (flag != null && flag == 1) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    public DeliveredStat deliveredStat(long riderId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                                SELECT COUNT(*) AS task_count,
                                       COALESCE(SUM(CASE WHEN is_on_time = 1 THEN 1 ELSE 0 END), 0) AS on_time_count
                                FROM delivery_task
                                WHERE rider_id = ? AND status = 'DELIVERED'
                                  AND delivered_at >= ? AND delivered_at < ?
                                """,
                        (resultSet, rowNum) -> new DeliveredStat(
                                resultSet.getInt("task_count"), resultSet.getInt("on_time_count")),
                        riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to))
                .stream()
                .findFirst()
                .orElse(new DeliveredStat(0, 0));
    }

    private static Boolean hasElevator(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        int value = resultSet.getInt("has_elevator");
        return resultSet.wasNull() ? null : value != 0;
    }

    public record TaskEarningRow(
            long taskId,
            String taskNo,
            Long riderId,
            String status,
            String groupKey,
            Integer floorNo,
            BigDecimal totalWeightKg,
            int actualDistanceMeters,
            int planDistanceMeters,
            LocalDateTime deliveredAt,
            LocalDateTime createdAt,
            Boolean hasElevator
    ) {
    }

    public record DeliveredStat(int taskCount, int onTimeCount) {
    }
}
