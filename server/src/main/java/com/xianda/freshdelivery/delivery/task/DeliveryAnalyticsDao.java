package com.xianda.freshdelivery.delivery.task;

import static com.xianda.freshdelivery.delivery.task.TaskRowReader.date;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryAnalyticsDao {
    private final JdbcTemplate jdbcTemplate;

    public DeliveryAnalyticsDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int countTasks(LocalDate from, LocalDate to) {
        return count("SELECT COUNT(*) FROM delivery_task WHERE delivery_date >= ? AND delivery_date <= ?", from, to);
    }

    public int countByStatus(LocalDate from, LocalDate to, String status) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_task WHERE delivery_date >= ? AND delivery_date <= ? AND status = ?",
                Integer.class, date(from), date(to), status
        );
        return value == null ? 0 : value;
    }

    public int countOnTime(LocalDate from, LocalDate to) {
        return count(
                "SELECT COUNT(*) FROM delivery_task WHERE delivery_date >= ? AND delivery_date <= ?"
                        + " AND status = 'DELIVERED' AND is_on_time = 1",
                from, to
        );
    }

    public int countOnTimeJudged(LocalDate from, LocalDate to) {
        return count(
                "SELECT COUNT(*) FROM delivery_task WHERE delivery_date >= ? AND delivery_date <= ?"
                        + " AND status = 'DELIVERED' AND is_on_time IS NOT NULL",
                from, to
        );
    }

    public int activeRiderDays(LocalDate from, LocalDate to) {
        return count(
                "SELECT COUNT(*) FROM (SELECT DISTINCT rider_id, delivery_date FROM delivery_task"
                        + " WHERE delivery_date >= ? AND delivery_date <= ? AND rider_id IS NOT NULL) t",
                from, to
        );
    }

    public List<Integer> deliveryDurationSeconds(LocalDate from, LocalDate to, Long riderId) {
        StringBuilder sql = new StringBuilder(
                "SELECT picked_up_at, delivered_at FROM delivery_task WHERE delivery_date >= ? AND delivery_date <= ?"
                        + " AND status = 'DELIVERED' AND picked_up_at IS NOT NULL AND delivered_at IS NOT NULL"
        );
        List<Object> args = new ArrayList<>(List.of(date(from), date(to)));
        if (riderId != null) {
            sql.append(" AND rider_id = ?");
            args.add(riderId);
        }
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            LocalDateTime pickedUp = TaskRowReader.dateTimeValue(rs, "picked_up_at");
            LocalDateTime delivered = TaskRowReader.dateTimeValue(rs, "delivered_at");
            return (int) Math.max(Duration.between(pickedUp, delivered).getSeconds(), 0);
        }, args.toArray());
    }

    public Map<Integer, Integer> hourlyTaskCounts(LocalDate from, LocalDate to) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT HOUR(COALESCE(picked_ready_at, created_at)) AS h, COUNT(*) AS c FROM delivery_task"
                        + " WHERE delivery_date >= ? AND delivery_date <= ? GROUP BY HOUR(COALESCE(picked_ready_at, created_at))",
                rs -> {
                    counts.put(rs.getInt("h"), rs.getInt("c"));
                },
                date(from), date(to)
        );
        return counts;
    }

    public List<BuildingDifficultyRow> buildingDifficulty(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query("""
                SELECT group_key, MIN(area_label) AS area_label, MIN(building_label) AS building_label,
                       AVG(handoff_seconds) AS avg_handoff, COUNT(*) AS sample_count
                FROM delivery_task
                WHERE delivery_date >= ? AND delivery_date <= ? AND handoff_seconds IS NOT NULL AND group_key IS NOT NULL
                GROUP BY group_key
                ORDER BY AVG(handoff_seconds) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new BuildingDifficultyRow(
                        rs.getString("group_key"),
                        rs.getString("area_label"),
                        rs.getString("building_label"),
                        (int) Math.round(rs.getDouble("avg_handoff")),
                        rs.getInt("sample_count")
                ),
                date(from), date(to), limit
        );
    }

    public Map<String, Integer> accessDifficulty(List<String> groupKeys) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (groupKeys == null || groupKeys.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", groupKeys.stream().map(key -> "?").toList());
        try {
            jdbcTemplate.query(
                    "SELECT group_key, MAX(access_difficulty) AS difficulty FROM building_handoff_stat"
                            + " WHERE group_key IN (" + placeholders + ") GROUP BY group_key",
                    rs -> {
                        result.put(rs.getString("group_key"), rs.getInt("difficulty"));
                    },
                    groupKeys.toArray()
            );
        } catch (DataAccessException ignored) {
            return result;
        }
        return result;
    }

    public Map<String, Integer> exceptionDistribution(LocalDate from, LocalDate to) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            jdbcTemplate.query("""
                    SELECT exception_type, COUNT(*) AS c FROM delivery_exception
                    WHERE created_at >= ? AND created_at < ?
                    GROUP BY exception_type ORDER BY COUNT(*) DESC
                    """,
                    rs -> {
                        counts.put(rs.getString("exception_type"), rs.getInt("c"));
                    },
                    TaskRowReader.timestamp(from.atStartOfDay()),
                    TaskRowReader.timestamp(to.plusDays(1).atStartOfDay())
            );
        } catch (DataAccessException ignored) {
            return counts;
        }
        return counts;
    }

    public Map<Long, Integer> exceptionCountByRider(LocalDate from, LocalDate to) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        try {
            jdbcTemplate.query("""
                    SELECT rider_id, COUNT(*) AS c FROM delivery_exception
                    WHERE created_at >= ? AND created_at < ? AND rider_id IS NOT NULL
                    GROUP BY rider_id
                    """,
                    rs -> {
                        counts.put(rs.getLong("rider_id"), rs.getInt("c"));
                    },
                    TaskRowReader.timestamp(from.atStartOfDay()),
                    TaskRowReader.timestamp(to.plusDays(1).atStartOfDay())
            );
        } catch (DataAccessException ignored) {
            return counts;
        }
        return counts;
    }

    public List<RiderStatRow> riderStats(LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT rider_id,
                       COUNT(*) AS task_count,
                       SUM(CASE WHEN status = 'DELIVERED' THEN 1 ELSE 0 END) AS delivered_count,
                       SUM(CASE WHEN status = 'RETURNED' THEN 1 ELSE 0 END) AS returned_count,
                       SUM(CASE WHEN is_on_time = 1 THEN 1 ELSE 0 END) AS on_time_count,
                       SUM(CASE WHEN is_on_time IS NOT NULL THEN 1 ELSE 0 END) AS judged_count,
                       AVG(handoff_seconds) AS avg_handoff,
                       SUM(actual_distance_meters) AS distance_meters,
                       COUNT(DISTINCT delivery_date) AS active_days
                FROM delivery_task
                WHERE delivery_date >= ? AND delivery_date <= ? AND rider_id IS NOT NULL
                GROUP BY rider_id
                ORDER BY COUNT(*) DESC
                """,
                (rs, rowNum) -> new RiderStatRow(
                        rs.getLong("rider_id"),
                        rs.getInt("task_count"),
                        rs.getInt("delivered_count"),
                        rs.getInt("returned_count"),
                        rs.getInt("on_time_count"),
                        rs.getInt("judged_count"),
                        rs.getObject("avg_handoff") == null ? null : (int) Math.round(rs.getDouble("avg_handoff")),
                        rs.getInt("distance_meters"),
                        rs.getInt("active_days")
                ),
                date(from), date(to)
        );
    }

    private int count(String sql, LocalDate from, LocalDate to) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, date(from), date(to));
        return value == null ? 0 : value;
    }

    public record BuildingDifficultyRow(
            String groupKey,
            String areaLabel,
            String buildingLabel,
            Integer avgHandoffSeconds,
            Integer sampleCount
    ) {
    }

    public record RiderStatRow(
            Long riderId,
            Integer taskCount,
            Integer deliveredCount,
            Integer returnedCount,
            Integer onTimeCount,
            Integer judgedCount,
            Integer avgHandoffSeconds,
            Integer distanceMeters,
            Integer activeDays
    ) {
    }
}
