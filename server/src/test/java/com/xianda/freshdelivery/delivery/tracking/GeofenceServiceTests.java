package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GeofenceServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;
    private static final long TASK_ID = 9001L;
    private static final double DESTINATION_LAT = 30.1234567;
    private static final double DESTINATION_LNG = 120.7654321;

    private JdbcTemplate jdbcTemplate;
    private TrackingTestSupport.RecordingArrival arrival;
    private GeofenceService geofenceService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_geofence");
        arrival = new TrackingTestSupport.RecordingArrival();
        TrackingPorts ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                new TrackingTestSupport.RecordingNotify(),
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        geofenceService = new GeofenceService(
                new TrackingTaskDao(jdbcTemplate),
                new TrackingGeofenceDao(jdbcTemplate),
                arrival,
                ports);
        TrackingTestSupport.insertRider(jdbcTemplate, RIDER_ID, "张三", NOW.minusDays(7));
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(TASK_ID).orderId(5001L).riderId(RIDER_ID).status("DELIVERING")
                .destination(DESTINATION_LAT, DESTINATION_LNG).build());
    }

    @Test
    void marksArrivedAfterStayingInsideTheFenceForTheDwellWindow() {
        List<Long> firstPoint = geofenceService.evaluate(
                RIDER_ID, DESTINATION_LAT + 0.0003, DESTINATION_LNG, NOW);
        List<Long> tooEarly = geofenceService.evaluate(
                RIDER_ID, DESTINATION_LAT + 0.0002, DESTINATION_LNG, NOW.plusSeconds(20));
        List<Long> afterDwell = geofenceService.evaluate(
                RIDER_ID, DESTINATION_LAT + 0.0002, DESTINATION_LNG, NOW.plusSeconds(31));

        assertTrue(firstPoint.isEmpty());
        assertTrue(tooEarly.isEmpty());
        assertEquals(List.of(TASK_ID), afterDwell);
        assertEquals(List.of(TASK_ID), arrival.taskIds());
        assertEquals("ENTER", jdbcTemplate.queryForObject(
                "SELECT event_type FROM delivery_geofence_event ORDER BY id LIMIT 1", String.class));
        assertEquals("DWELL", jdbcTemplate.queryForObject(
                "SELECT event_type FROM delivery_geofence_event ORDER BY id DESC LIMIT 1", String.class));
        assertEquals("MARK_ARRIVED", jdbcTemplate.queryForObject(
                "SELECT auto_action FROM delivery_geofence_event ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void doesNotTriggerOutsideTheArriveRadius() {
        geofenceService.evaluate(RIDER_ID, DESTINATION_LAT + 0.0050, DESTINATION_LNG, NOW);
        List<Long> later = geofenceService.evaluate(
                RIDER_ID, DESTINATION_LAT + 0.0050, DESTINATION_LNG, NOW.plusSeconds(120));

        assertTrue(later.isEmpty());
        assertTrue(arrival.taskIds().isEmpty());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_geofence_event", Integer.class));
    }

    @Test
    void triggersOnlyOncePerTask() {
        geofenceService.evaluate(RIDER_ID, DESTINATION_LAT, DESTINATION_LNG, NOW);
        geofenceService.evaluate(RIDER_ID, DESTINATION_LAT, DESTINATION_LNG, NOW.plusSeconds(31));
        geofenceService.evaluate(RIDER_ID, DESTINATION_LAT, DESTINATION_LNG, NOW.plusSeconds(62));

        assertEquals(1, arrival.taskIds().size());
    }

    @Test
    void writesExitEventWhenRiderLeavesBeforeDwellCompletes() {
        geofenceService.evaluate(RIDER_ID, DESTINATION_LAT, DESTINATION_LNG, NOW);
        geofenceService.evaluate(RIDER_ID, DESTINATION_LAT + 0.0100, DESTINATION_LNG, NOW.plusSeconds(10));

        assertEquals("EXIT", jdbcTemplate.queryForObject(
                "SELECT event_type FROM delivery_geofence_event ORDER BY id DESC LIMIT 1", String.class));
        assertTrue(arrival.taskIds().isEmpty());
    }

    @Test
    void retriesArrivalAfterTheFirstActionFailureAndPersistsDwellOnlyAfterSuccess() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        TrackingArrivalPort flakyArrival = (taskId, lat, lng) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary transition failure");
            }
        };
        TrackingPorts ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                new TrackingTestSupport.RecordingNotify(),
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        GeofenceService retryingService = new GeofenceService(
                new TrackingTaskDao(jdbcTemplate),
                new TrackingGeofenceDao(jdbcTemplate),
                flakyArrival,
                ports);

        retryingService.evaluate(RIDER_ID, DESTINATION_LAT, DESTINATION_LNG, NOW);
        List<Long> failed = retryingService.evaluate(
                RIDER_ID, DESTINATION_LAT, DESTINATION_LNG, NOW.plusSeconds(31));

        assertTrue(failed.isEmpty());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_geofence_event WHERE event_type = 'DWELL'", Integer.class));

        List<Long> retried = retryingService.evaluate(
                RIDER_ID, DESTINATION_LAT, DESTINATION_LNG, NOW.plusSeconds(32));

        assertEquals(List.of(TASK_ID), retried);
        assertEquals(2, attempts.get());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_geofence_event WHERE event_type = 'DWELL'", Integer.class));
    }
}
