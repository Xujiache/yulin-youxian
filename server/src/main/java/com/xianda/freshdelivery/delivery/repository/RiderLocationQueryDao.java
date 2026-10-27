package com.xianda.freshdelivery.delivery.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RiderLocationQueryDao {
    private static final RowMapper<TrackPoint> ROW_MAPPER = (resultSet, rowNum) -> new TrackPoint(
            resultSet.getDouble("lat"),
            resultSet.getDouble("lng"),
            JdbcValues.doubleOrNull(resultSet, "speed_mps"),
            JdbcValues.doubleOrNull(resultSet, "bearing"),
            resultSet.getString("motion_state"),
            JdbcValues.dateTime(resultSet, "located_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public RiderLocationQueryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TrackPoint> findTrack(long riderId, LocalDateTime from, LocalDateTime to, int hardLimit) {
        return jdbcTemplate.query("""
                        SELECT lat, lng, speed_mps, bearing, motion_state, located_at
                        FROM rider_location
                        WHERE rider_id = ? AND located_at >= ? AND located_at < ?
                        ORDER BY located_at
                        LIMIT ?
                        """,
                ROW_MAPPER,
                riderId,
                JdbcValues.timestamp(from),
                JdbcValues.timestamp(to),
                hardLimit);
    }

    public int countTrack(long riderId, LocalDateTime from, LocalDateTime to) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM rider_location
                        WHERE rider_id = ? AND located_at >= ? AND located_at < ?
                        """,
                Integer.class, riderId, JdbcValues.timestamp(from), JdbcValues.timestamp(to));
        return count == null ? 0 : count;
    }

    public record TrackPoint(
            Double lat,
            Double lng,
            Double speedMps,
            Double bearing,
            String motionState,
            LocalDateTime locatedAt
    ) {
    }
}
