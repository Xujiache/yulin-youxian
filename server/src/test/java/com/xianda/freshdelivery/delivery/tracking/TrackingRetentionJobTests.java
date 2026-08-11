package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TrackingRetentionJobTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 3, 30, 0);
    private static final long RIDER_ID = 1L;

    private JdbcTemplate jdbcTemplate;
    private TrackingRetentionJob retentionJob;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_retention");
        TrackingPorts ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                new TrackingTestSupport.RecordingNotify(),
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        retentionJob = new TrackingRetentionJob(
                new TrackingLocationDao(jdbcTemplate), ports, TrackingTestSupport.clock(NOW));
    }

    @Test
    void deletesExpiredTrackInBatchesAndKeepsRecentRows() {
        for (int index = 0; index < 12; index++) {
            TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID,
                    NOW.minusDays(120).plusSeconds(index), 30.10, 120.70);
        }
        for (int index = 0; index < 4; index++) {
            TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID,
                    NOW.minusDays(2).plusSeconds(index), 30.11, 120.71);
        }
        insertGeofenceEvent(NOW.minusDays(200));
        insertGeofenceEvent(NOW.minusDays(30));

        TrackingRetentionJob.RetentionResult result = retentionJob.purge();

        assertEquals(12, result.locationsDeleted());
        assertEquals(1, result.geofenceEventsDeleted());
        assertTrue(result.locationBatches() >= 1);
        assertEquals(4, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_geofence_event", Integer.class));
    }

    @Test
    void loopsUntilEveryExpiredBatchIsRemoved() {
        int rows = TrackingRetentionJob.BATCH_SIZE + 37;
        jdbcTemplate.batchUpdate("""
                        INSERT INTO rider_location (rider_id, lat, lng, located_at, motion_state, is_cleaned)
                        VALUES (?, 30.10, 120.70, ?, 'RIDING', 1)
                        """,
                new java.util.AbstractList<Object[]>() {
                    @Override
                    public Object[] get(int index) {
                        return new Object[]{RIDER_ID,
                                Timestamp.valueOf(NOW.minusDays(120).plusSeconds(index))};
                    }

                    @Override
                    public int size() {
                        return rows;
                    }
                });

        TrackingRetentionJob.RetentionResult result = retentionJob.purge();

        assertEquals(rows, result.locationsDeleted());
        assertEquals(2, result.locationBatches());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
    }

    @Test
    void removesLatestForExpiredOffDutyRevokedAndResignedRiders() {
        TrackingTestSupport.insertRider(jdbcTemplate, 1L, "过期骑手", NOW.minusDays(10));
        TrackingTestSupport.openShift(jdbcTemplate, 101L, 1L, NOW.minusHours(2));
        TrackingTestSupport.insertLatest(jdbcTemplate, 1L, NOW.minusDays(120), 30.10, 120.70);

        TrackingTestSupport.insertRider(jdbcTemplate, 2L, "离岗骑手", NOW.minusDays(10), "OFF_DUTY");
        TrackingTestSupport.insertLatest(jdbcTemplate, 2L, NOW.minusMinutes(2), 30.11, 120.71);

        TrackingTestSupport.insertRider(jdbcTemplate, 3L, "撤回同意", null);
        TrackingTestSupport.openShift(jdbcTemplate, 103L, 3L, NOW.minusHours(2));
        TrackingTestSupport.insertLatest(jdbcTemplate, 3L, NOW.minusMinutes(2), 30.12, 120.72);

        TrackingTestSupport.insertRider(jdbcTemplate, 4L, "离职骑手", NOW.minusDays(10));
        TrackingTestSupport.openShift(jdbcTemplate, 104L, 4L, NOW.minusHours(2));
        jdbcTemplate.update("UPDATE rider SET account_status = 'RESIGNED' WHERE id = 4");
        TrackingTestSupport.insertLatest(jdbcTemplate, 4L, NOW.minusMinutes(2), 30.13, 120.73);

        TrackingTestSupport.insertRider(jdbcTemplate, 5L, "在岗骑手", NOW.minusDays(10));
        TrackingTestSupport.openShift(jdbcTemplate, 105L, 5L, NOW.minusHours(2));
        TrackingTestSupport.insertLatest(jdbcTemplate, 5L, NOW.minusMinutes(2), 30.14, 120.74);

        TrackingRetentionJob.RetentionResult result = retentionJob.purge();

        assertEquals(4, result.latestDeleted());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_location_latest", Integer.class));
        assertEquals(5L, jdbcTemplate.queryForObject(
                "SELECT rider_id FROM rider_location_latest", Long.class));
    }

    private void insertGeofenceEvent(LocalDateTime occurredAt) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_geofence_event
                            (rider_id, task_id, zone_type, event_type, lat, lng, distance_meters, occurred_at)
                        VALUES (?, 9001, 'CUSTOMER', 'ENTER', 30.10, 120.70, 40, ?)
                        """,
                RIDER_ID, Timestamp.valueOf(occurredAt));
    }
}
