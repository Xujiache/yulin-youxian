package com.xianda.freshdelivery.delivery.repository;

import com.xianda.freshdelivery.delivery.common.GeoPoint;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiderStatsDao {
    private final JdbcTemplate jdbcTemplate;

    public RiderStatsDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ShiftTaskStats shiftTaskStats(long riderId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                        SELECT
                            COALESCE(SUM(CASE WHEN (COALESCE(accepted_at, assigned_at) >= ?
                                                    AND COALESCE(accepted_at, assigned_at) <= ?)
                                                OR (delivered_at >= ? AND delivered_at <= ?)
                                              THEN 1 ELSE 0 END), 0) AS task_count,
                            COALESCE(SUM(CASE WHEN status = 'DELIVERED'
                                                AND delivered_at >= ? AND delivered_at <= ?
                                              THEN 1 ELSE 0 END), 0) AS delivered_count,
                            COALESCE(SUM(CASE WHEN status = 'DELIVERED' AND is_on_time = 1
                                                AND delivered_at >= ? AND delivered_at <= ?
                                              THEN 1 ELSE 0 END), 0) AS on_time_count
                        FROM delivery_task
                        WHERE rider_id = ?
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return new ShiftTaskStats(0, 0, 0);
                    }
                    return new ShiftTaskStats(
                            resultSet.getInt("task_count"),
                            resultSet.getInt("delivered_count"),
                            resultSet.getInt("on_time_count"));
                },
                JdbcValues.timestamp(from), JdbcValues.timestamp(to),
                JdbcValues.timestamp(from), JdbcValues.timestamp(to),
                JdbcValues.timestamp(from), JdbcValues.timestamp(to),
                JdbcValues.timestamp(from), JdbcValues.timestamp(to),
                riderId);
    }

    public LifetimeTaskStats lifetimeTaskStats(long riderId) {
        return jdbcTemplate.query("""
                        SELECT COUNT(*) AS total_count,
                               COALESCE(SUM(CASE WHEN is_on_time = 1 THEN 1 ELSE 0 END), 0) AS on_time_count
                        FROM delivery_task
                        WHERE rider_id = ? AND status = 'DELIVERED'
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return new LifetimeTaskStats(0, 0);
                    }
                    return new LifetimeTaskStats(
                            resultSet.getInt("total_count"),
                            resultSet.getInt("on_time_count"));
                },
                riderId);
    }

    public int countExceptions(long riderId, LocalDateTime from, LocalDateTime to) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM delivery_exception
                        WHERE rider_id = ? AND created_at >= ? AND created_at <= ?
                        """,
                Integer.class, riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
        return count == null ? 0 : count;
    }

    public int sumEarningAmount(long riderId, LocalDateTime from, LocalDateTime to) {
        Integer total = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0) FROM delivery_settlement_item
                        WHERE rider_id = ? AND occurred_at >= ? AND occurred_at <= ?
                        """,
                Integer.class, riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
        return total == null ? 0 : total;
    }

    public List<GeoPoint> shiftTrack(long shiftId) {
        return jdbcTemplate.query("""
                        SELECT lat, lng FROM rider_location
                        WHERE shift_id = ? AND is_cleaned = 1
                        ORDER BY located_at, id
                        """,
                (resultSet, rowNum) -> new GeoPoint(
                        resultSet.getDouble("lat"),
                        resultSet.getDouble("lng")),
                shiftId);
    }

    public record ShiftTaskStats(int taskCount, int deliveredCount, int onTimeCount) {
    }

    public record LifetimeTaskStats(int totalCount, int onTimeCount) {
    }
}
