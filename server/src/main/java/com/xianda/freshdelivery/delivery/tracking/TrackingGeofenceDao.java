package com.xianda.freshdelivery.delivery.tracking;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrackingGeofenceDao {
    public static final String ZONE_CUSTOMER = "CUSTOMER";
    public static final String EVENT_ENTER = "ENTER";
    public static final String EVENT_EXIT = "EXIT";
    public static final String EVENT_DWELL = "DWELL";
    public static final String ACTION_MARK_ARRIVED = "MARK_ARRIVED";

    private final JdbcTemplate jdbcTemplate;

    public TrackingGeofenceDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<GeofenceEventRow> lastEvent(long riderId, long taskId) {
        return jdbcTemplate.query("""
                                SELECT id, event_type, distance_meters, occurred_at, auto_action
                                FROM delivery_geofence_event
                                WHERE rider_id = ? AND task_id = ? AND zone_type = 'CUSTOMER'
                                ORDER BY id DESC LIMIT 1
                                """,
                        (resultSet, rowNum) -> new GeofenceEventRow(
                                resultSet.getLong("id"),
                                resultSet.getString("event_type"),
                                resultSet.getInt("distance_meters"),
                                resultSet.getTimestamp("occurred_at") == null
                                        ? null
                                        : resultSet.getTimestamp("occurred_at").toLocalDateTime(),
                                resultSet.getString("auto_action")),
                        riderId, taskId)
                .stream()
                .findFirst();
    }

    public void insert(
            long riderId,
            Long taskId,
            String eventType,
            double lat,
            double lng,
            int distanceMeters,
            Integer dwellSeconds,
            String autoAction,
            LocalDateTime occurredAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_geofence_event
                            (rider_id, task_id, zone_type, event_type, lat, lng, distance_meters,
                             dwell_seconds, auto_action, occurred_at)
                        VALUES (?, ?, 'CUSTOMER', ?, ?, ?, ?, ?, ?, ?)
                        """,
                riderId, taskId, eventType, lat, lng, distanceMeters, dwellSeconds, autoAction,
                TrackingTimes.timestamp(occurredAt));
    }

    public record GeofenceEventRow(
            Long id,
            String eventType,
            Integer distanceMeters,
            LocalDateTime occurredAt,
            String autoAction
    ) {
    }
}
