package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TrackingLocationDaoTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);

    private JdbcTemplate jdbcTemplate;
    private TrackingLocationDao locationDao;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("tracking_location_dao");
        locationDao = new TrackingLocationDao(jdbcTemplate);
    }

    @Test
    void insertIgnoreKeepsDuplicatePointsOutOfTheTable() {
        List<TrackingLocationDao.PointRow> rows = List.of(point(NOW, 30.10), point(NOW.plusSeconds(10), 30.11));

        locationDao.insertPoints(rows);
        locationDao.insertPoints(rows);

        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
    }

    @Test
    void upsertLatestKeepsSingleRowAndIgnoresStalePoints() {
        locationDao.upsertLatest(latest(NOW, 30.10));
        locationDao.upsertLatest(latest(NOW.plusSeconds(10), 30.20));
        locationDao.upsertLatest(latest(NOW.minusSeconds(60), 30.99));

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location_latest", Integer.class));
        TrackingLocationDao.LatestRow stored = locationDao.findLatest(1L).orElseThrow();
        assertEquals(30.20, stored.lat(), 0.0000001);
        assertEquals(NOW.plusSeconds(10), stored.locatedAt());
    }

    @Test
    void equalTimestampCannotRewriteLatestButAQuarantinedFutureTimestampCanBeRecovered() {
        assertTrue(locationDao.upsertLatest(latest(NOW, 30.10)));
        assertEquals(false, locationDao.upsertLatest(latest(NOW, 30.99)));
        assertEquals(30.10, locationDao.findLatest(1L).orElseThrow().lat(), 0.0000001);

        jdbcTemplate.update(
                "UPDATE rider_location_latest SET lat = 31.99, located_at = ? WHERE rider_id = 1",
                TrackingTestSupport.timestamp(NOW.plusDays(30)));

        assertTrue(locationDao.upsertLatest(latest(NOW.plusSeconds(5), 30.20), NOW.plusMinutes(1)));
        TrackingLocationDao.LatestRow recovered = locationDao.findLatest(1L).orElseThrow();
        assertEquals(30.20, recovered.lat(), 0.0000001);
        assertEquals(NOW.plusSeconds(5), recovered.locatedAt());
    }

    @Test
    void deletesExpiredLocationsInBoundedBatches() {
        for (int index = 0; index < 12; index++) {
            locationDao.insertPoints(List.of(point(NOW.minusDays(100).plusSeconds(index), 30.10)));
        }
        locationDao.insertPoints(List.of(point(NOW, 30.10)));

        int firstBatch = locationDao.deleteLocationsBefore(NOW.minusDays(90), 5);
        int secondBatch = locationDao.deleteLocationsBefore(NOW.minusDays(90), 5);
        int thirdBatch = locationDao.deleteLocationsBefore(NOW.minusDays(90), 5);
        int fourthBatch = locationDao.deleteLocationsBefore(NOW.minusDays(90), 5);

        assertEquals(5, firstBatch);
        assertEquals(5, secondBatch);
        assertEquals(2, thirdBatch);
        assertEquals(0, fourthBatch);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
    }

    @Test
    void downsamplesHistoryToAtMostOneThousandPoints() {
        List<TrackingLocationDao.HistoryPoint> points = new java.util.ArrayList<>();
        for (int index = 0; index < 4200; index++) {
            points.add(new TrackingLocationDao.HistoryPoint(
                    30.10 + index * 0.00001, 120.70, null, null, "RIDING", NOW.plusSeconds(index)));
        }

        List<TrackingLocationDao.HistoryPoint> sampled = TrackingLocationDao.downsample(points, 1000);

        assertEquals(1000, sampled.size());
        assertEquals(points.get(0), sampled.get(0));
        assertEquals(points.get(points.size() - 1), sampled.get(sampled.size() - 1));
        assertTrue(sampled.size() <= 1000);
    }

    private TrackingLocationDao.PointRow point(LocalDateTime locatedAt, double lat) {
        return new TrackingLocationDao.PointRow(
                1L, null, null, lat, 120.70, 10, 4.2, 178.5, 15.2,
                "GPS", 68, "5G", "RIDING", true, locatedAt, NOW, "batch-1");
    }

    private TrackingLocationDao.LatestRow latest(LocalDateTime locatedAt, double lat) {
        return new TrackingLocationDao.LatestRow(
                1L, lat, 120.70, 10, 4.2, 178.5, 68, "RIDING", null, null, locatedAt);
    }
}
