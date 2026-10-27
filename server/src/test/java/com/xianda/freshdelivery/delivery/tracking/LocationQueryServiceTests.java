package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.dto.RiderTrackDto;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LocationQueryServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;

    private JdbcTemplate jdbcTemplate;
    private LocationQueryService locationQueryService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_location_query");
        locationQueryService = new LocationQueryService(
                new TrackingLocationDao(jdbcTemplate), TrackingTestSupport.clock(NOW));
    }

    @Test
    void downsamplesLongHistoryToAtMostOneThousandPoints() {
        for (int index = 0; index < 3600; index++) {
            TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID,
                    NOW.minusHours(4).plusSeconds(index * 4L), 30.10 + index * 0.00001, 120.70);
        }

        RiderTrackDto track = locationQueryService.history(RIDER_ID, NOW.minusHours(5), NOW);

        assertTrue(track.points().size() <= LocationQueryService.MAX_TRACK_POINTS);
        assertEquals(LocationQueryService.MAX_TRACK_POINTS, track.points().size());
        assertEquals(TrackingTimes.format(NOW.minusHours(4)), track.points().get(0).locatedAt());
        assertEquals(TrackingTimes.format(NOW.minusHours(4).plusSeconds(3599 * 4L)),
                track.points().get(track.points().size() - 1).locatedAt());
    }

    @Test
    void keepsShortHistoryIntactAndSkipsUncleanedPoints() {
        TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, NOW.minusMinutes(3), 30.10, 120.70);
        TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, NOW.minusMinutes(2), 30.11, 120.71);
        jdbcTemplate.update("""
                INSERT INTO rider_location (rider_id, lat, lng, located_at, motion_state, is_cleaned)
                VALUES (1, 31.99, 121.99, ?, 'RIDING', 0)
                """, TrackingTestSupport.timestamp(NOW.minusMinutes(1)));

        RiderTrackDto track = locationQueryService.history(RIDER_ID, NOW.minusHours(1), NOW);

        assertEquals(2, track.points().size());
        assertEquals(30.11, track.points().get(1).lat(), 0.0000001);
    }

    @Test
    void currentPositionReadsOnlyTheHotTable() {
        TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, NOW.minusMinutes(9), 30.10, 120.70);
        TrackingTestSupport.insertLatest(jdbcTemplate, RIDER_ID, NOW.minusSeconds(8), 30.20, 120.80);

        TrackingLocationDao.LatestRow latest = locationQueryService.currentPosition(RIDER_ID).orElseThrow();

        assertEquals(30.20, latest.lat(), 0.0000001);
        assertEquals(NOW.minusSeconds(8), latest.locatedAt());
    }
}
