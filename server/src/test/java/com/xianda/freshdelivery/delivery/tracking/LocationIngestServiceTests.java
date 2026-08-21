package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.dto.LocationBatchRequest;
import com.xianda.freshdelivery.delivery.dto.LocationBatchResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LocationIngestServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;
    private static final double STORE_LAT = 30.10;
    private static final double STORE_LNG = 120.70;

    private JdbcTemplate jdbcTemplate;
    private TrackingLocationDao locationDao;
    private TrackingTestSupport.FixedTrackingConfig config;
    private TrackingTestSupport.RecordingArrival arrival;
    private LocationIngestService ingestService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_ingest");
        locationDao = new TrackingLocationDao(jdbcTemplate);
        config = new TrackingTestSupport.FixedTrackingConfig();
        arrival = new TrackingTestSupport.RecordingArrival();
        TrackingPorts ports = new TrackingPorts(
                config, new TrackingTestSupport.RecordingNotify(), new JdbcTrackingPrivacyNumber(jdbcTemplate));
        TrackingTaskDao taskDao = new TrackingTaskDao(jdbcTemplate);
        GeofenceService geofenceService = new GeofenceService(
                taskDao, new TrackingGeofenceDao(jdbcTemplate), arrival, ports);
        ingestService = new LocationIngestService(
                locationDao,
                new TrackingRiderDao(jdbcTemplate),
                taskDao,
                new TrackCleaner(),
                geofenceService,
                new DeliveryEventStream(() -> 0L),
                new TrackingIngestMetrics(() -> 0L),
                ports,
                TrackingTestSupport.clock(NOW));

        TrackingTestSupport.insertRider(jdbcTemplate, RIDER_ID, "张三", NOW.minusDays(7));
        TrackingTestSupport.openShift(jdbcTemplate, 100L, RIDER_ID, NOW.minusHours(2));
    }

    @Test
    void replayingTheSameBatchDoesNotGrowTheLocationTable() {
        LocationBatchRequest request = batch(
                point(NOW.minusSeconds(20), STORE_LAT, STORE_LNG, 12, "RIDING"),
                point(NOW.minusSeconds(10), STORE_LAT + 0.0005, STORE_LNG, 12, "RIDING"));

        LocationBatchResponse first = ingestService.ingest(RIDER_ID, request).response();
        LocationBatchResponse second = ingestService.ingest(RIDER_ID, request).response();

        assertEquals(2, first.accepted());
        assertEquals(2, second.accepted());
        assertEquals(0, second.rejected());
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location_latest", Integer.class));
    }

    @Test
    void rejectsEveryPointWhenLocationConsentIsMissing() {
        TrackingTestSupport.insertLatest(jdbcTemplate, RIDER_ID, NOW.minusSeconds(20), STORE_LAT, STORE_LNG);
        jdbcTemplate.update("UPDATE rider SET location_consent_at = NULL WHERE id = ?", RIDER_ID);

        LocationIngestResult result = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(10), STORE_LAT, STORE_LNG, 12, "RIDING")));

        assertTrue(result.consentBlocked());
        assertEquals(0, result.response().accepted());
        assertEquals(1, result.response().rejected());
        assertEquals(LocationIngestService.REASON_CONSENT, result.response().rejectReasons().get(0).reason());
        assertEquals(0, result.response().rejectReasons().get(0).index());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_location_latest", Integer.class));
    }

    @Test
    void rejectsPointsWhenRiderHasNoOpenShift() {
        jdbcTemplate.update("UPDATE rider_shift SET off_duty_at = ? WHERE id = 100",
                TrackingTestSupport.timestamp(NOW.minusMinutes(5)));

        LocationIngestResult result = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(10), STORE_LAT, STORE_LNG, 12, "RIDING")));

        assertFalse(result.consentBlocked());
        assertEquals(0, result.response().accepted());
        assertEquals(LocationIngestService.REASON_OFF_DUTY, result.response().rejectReasons().get(0).reason());
    }

    @Test
    void rejectsPointsWorseThanTheConfiguredAccuracy() {
        LocationIngestResult result = ingestService.ingest(RIDER_ID, batch(
                point(NOW.minusSeconds(20), STORE_LAT, STORE_LNG, 12, "RIDING"),
                point(NOW.minusSeconds(10), STORE_LAT, STORE_LNG, 240, "RIDING")));

        assertEquals(1, result.response().accepted());
        assertEquals(1, result.response().rejected());
        assertEquals(1, result.response().rejectReasons().get(0).index());
        assertEquals(LocationIngestService.REASON_ACCURACY_TOO_LOW,
                result.response().rejectReasons().get(0).reason());
    }

    @Test
    void rejectsPointsBeyondTheGpsSanityRadius() {
        LocationIngestResult result = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(10), 39.90, 116.40, 12, "RIDING")));

        assertEquals(0, result.response().accepted());
        assertEquals(LocationIngestService.REASON_GPS_DRIFT, result.response().rejectReasons().get(0).reason());
    }

    @Test
    void acceptsLegitimatePositionOfARiderFifteenKilometersFromTheStore() {
        double farLat = STORE_LAT + 0.135;

        LocationIngestResult result = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(10), farLat, STORE_LNG, 12, "RIDING")));

        assertEquals(1, result.response().accepted());
        assertEquals(0, result.response().rejected());
        assertTrue(result.response().rejectReasons().isEmpty());
        TrackingLocationDao.LatestRow latest = locationDao.findLatest(RIDER_ID).orElseThrow();
        assertEquals(farLat, latest.lat(), 0.0005);
    }

    @Test
    void downgradesReportIntervalWhenRiderIsStill() {
        LocationBatchResponse still = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(30), STORE_LAT, STORE_LNG, 12, "STILL"))).response();
        LocationBatchResponse walking = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(20), STORE_LAT, STORE_LNG, 12, "WALKING"))).response();
        LocationBatchResponse riding = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(10), STORE_LAT, STORE_LNG, 12, "RIDING"))).response();

        assertEquals(60, still.nextIntervalSeconds());
        assertEquals(20, walking.nextIntervalSeconds());
        assertEquals(10, riding.nextIntervalSeconds());
    }

    @Test
    void sendsRefreshTasksCommandWhenAssignedTaskWaits() {
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9001L).orderId(5001L).riderId(RIDER_ID).status("ASSIGNED")
                .destination(STORE_LAT, STORE_LNG).build());

        LocationBatchResponse response = ingestService.ingest(RIDER_ID,
                batch(point(NOW.minusSeconds(10), STORE_LAT, STORE_LNG, 12, "RIDING"))).response();

        assertNotNull(response.commands());
        assertEquals(1, response.commands().size());
        assertEquals(LocationIngestService.COMMAND_REFRESH_TASKS, response.commands().get(0).type());
    }

    @Test
    void marksJumpPointsAsNotCleanedAndKeepsLatestOnTheSmoothedTrack() {
        LocationBatchResponse response = ingestService.ingest(RIDER_ID, batch(
                point(NOW.minusSeconds(30), STORE_LAT, STORE_LNG, 8, "RIDING"),
                point(NOW.minusSeconds(20), STORE_LAT + 0.0004, STORE_LNG, 8, "RIDING"),
                point(NOW.minusSeconds(15), STORE_LAT + 0.0200, STORE_LNG, 8, "RIDING"))).response();

        assertEquals(3, response.accepted());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rider_location WHERE is_cleaned = 0", Integer.class));
        TrackingLocationDao.LatestRow latest = locationDao.findLatest(RIDER_ID).orElseThrow();
        assertEquals(NOW.minusSeconds(20), latest.locatedAt());
        assertTrue(latest.lat() < STORE_LAT + 0.001);
    }

    @Test
    void rejectsFuturePoisonAndAllowsANormalPointToReplaceAnAlreadyPoisonedLatestRow() {
        TrackingTestSupport.insertLatest(
                jdbcTemplate, RIDER_ID, NOW.plusDays(30), STORE_LAT + 0.01, STORE_LNG + 0.01);

        LocationBatchResponse response = ingestService.ingest(RIDER_ID, batch(
                point(NOW.plusSeconds(LocationIngestService.MAX_FUTURE_SKEW_SECONDS + 1L),
                        STORE_LAT + 0.02, STORE_LNG, 12, "RIDING"),
                point(NOW.minusSeconds(5), STORE_LAT, STORE_LNG, 12, "RIDING"))).response();

        assertEquals(1, response.accepted());
        assertEquals(1, response.rejected());
        assertEquals(LocationIngestService.REASON_FUTURE_TIME, response.rejectReasons().get(0).reason());
        TrackingLocationDao.LatestRow latest = locationDao.findLatest(RIDER_ID).orElseThrow();
        assertEquals(NOW.minusSeconds(5), latest.locatedAt());
        assertEquals(STORE_LAT, latest.lat(), 0.0000001);
    }

    @Test
    void rejectsExpiredTimestampsAndCoordinatesOutsideTheEarth() {
        LocationBatchResponse response = ingestService.ingest(RIDER_ID, batch(
                point(NOW.minusHours(LocationIngestService.MAX_POINT_AGE_HOURS + 1L),
                        STORE_LAT, STORE_LNG, 12, "RIDING"),
                point(NOW.minusSeconds(5), 91d, STORE_LNG, 12, "RIDING"),
                point(NOW.minusSeconds(4), STORE_LAT, 181d, 12, "RIDING"))).response();

        assertEquals(0, response.accepted());
        assertEquals(3, response.rejected());
        assertEquals(LocationIngestService.REASON_STALE_TIME, response.rejectReasons().get(0).reason());
        assertEquals(LocationIngestService.REASON_INVALID_POINT, response.rejectReasons().get(1).reason());
        assertEquals(LocationIngestService.REASON_INVALID_POINT, response.rejectReasons().get(2).reason());
    }

    @Test
    void derivesTaskAndWaveAssociationOnTheServerInsteadOfTrustingTheBatch() {
        TrackingTestSupport.insertWave(jdbcTemplate, 501L, RIDER_ID, NOW.toLocalDate());
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9001L).orderId(5001L).waveId(501L).riderId(RIDER_ID).status("DELIVERING")
                .destination(STORE_LAT, STORE_LNG).build());
        TrackingTestSupport.insertWaveStop(jdbcTemplate, 501L, 9001L, 1);
        LocationBatchRequest forged = new LocationBatchRequest(
                "forged-association",
                List.of(point(NOW.minusSeconds(5), STORE_LAT, STORE_LNG, 12, "RIDING")),
                999_999L,
                888_888L);

        ingestService.ingest(RIDER_ID, forged);

        assertEquals(501L, jdbcTemplate.queryForObject(
                "SELECT wave_id FROM rider_location WHERE rider_id = ?", Long.class, RIDER_ID));
        TrackingLocationDao.LatestRow latest = locationDao.findLatest(RIDER_ID).orElseThrow();
        assertEquals(501L, latest.waveId());
        assertEquals(9001L, latest.currentTaskId());
    }

    @Test
    void rejectsPointsBeyondTheHardBatchLimit() {
        java.util.ArrayList<LocationBatchRequest.LocationPointDto> points = new java.util.ArrayList<>();
        for (int index = 0; index < LocationIngestService.MAX_BATCH_POINTS + 1; index++) {
            points.add(point(NOW.minusSeconds(LocationIngestService.MAX_BATCH_POINTS + 1L - index),
                    STORE_LAT, STORE_LNG, 12, "RIDING"));
        }

        LocationBatchResponse response = ingestService.ingest(
                RIDER_ID, new LocationBatchRequest("oversized", points, null, null)).response();

        assertEquals(LocationIngestService.MAX_BATCH_POINTS, response.accepted());
        assertEquals(1, response.rejected());
        assertEquals(LocationIngestService.REASON_BATCH_LIMIT, response.rejectReasons().get(0).reason());
        assertEquals(LocationIngestService.MAX_BATCH_POINTS,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
    }

    private LocationBatchRequest batch(LocationBatchRequest.LocationPointDto... points) {
        return new LocationBatchRequest("batch-uuid-1", List.of(points), null, null);
    }

    private LocationBatchRequest.LocationPointDto point(
            LocalDateTime locatedAt,
            double lat,
            double lng,
            Integer accuracy,
            String motionState
    ) {
        return new LocationBatchRequest.LocationPointDto(
                lat, lng, accuracy, 4.2, 178.5, 15.2, "GPS", motionState, 68, "5G",
                TrackingTimes.format(locatedAt));
    }
}
