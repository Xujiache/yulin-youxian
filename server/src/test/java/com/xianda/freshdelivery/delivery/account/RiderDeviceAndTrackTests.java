package com.xianda.freshdelivery.delivery.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderDevice;
import com.xianda.freshdelivery.delivery.dto.DeviceReportRequest;
import com.xianda.freshdelivery.delivery.dto.RiderTrackDto;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import com.xianda.freshdelivery.delivery.repository.RiderDeviceDao;
import com.xianda.freshdelivery.delivery.repository.RiderLocationQueryDao;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RiderDeviceAndTrackTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 9, 0, 0);
    private static final long RIDER_ID = 1L;

    private JdbcTemplate jdbcTemplate;
    private RiderDeviceService riderDeviceService;
    private RiderTrackQueryService riderTrackQueryService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = DeliveryTestDatabase.create("rider_device_track");
        MutableClock clock = new MutableClock(NOW);
        riderDeviceService = new RiderDeviceService(new RiderDeviceDao(jdbcTemplate), clock);
        riderTrackQueryService = new RiderTrackQueryService(new RiderLocationQueryDao(jdbcTemplate), clock);
    }

    @Test
    void upsertsDeviceAndKeepsPushRegistrationId() {
        riderDeviceService.report(RIDER_ID, new DeviceReportRequest(
                "android-1", "Xiaomi", "23127PN0CC", "16", "1.0.0",
                "jpush-registration-1", "JPUSH", true, true, true, true));

        RiderDevice stored = riderDeviceService.report(RIDER_ID, new DeviceReportRequest(
                "android-1", null, null, null, "1.0.1", null, null, null, null, null, null));

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_device", Integer.class));
        assertEquals("1.0.1", stored.appVersion());
        assertEquals("Xiaomi", stored.manufacturer());
        assertEquals("jpush-registration-1", stored.pushRegistrationId());
        assertTrue(stored.notificationEnabled());
    }

    @Test
    void rejectsDeviceReportWithoutDeviceId() {
        assertThrows(DeliveryException.class, () -> riderDeviceService.report(RIDER_ID,
                new DeviceReportRequest(null, null, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void downsamplesTrackToAtMostOneThousandPoints() {
        for (int index = 0; index < 2500; index++) {
            insertLocation(NOW.minusHours(3).plusSeconds(index * 4L), 30.10 + index * 0.00001, 120.70);
        }

        RiderTrackDto track = riderTrackQueryService.track(RIDER_ID, LocalDate.of(2026, 8, 11), null, null);

        assertTrue(track.points().size() <= RiderTrackQueryService.MAX_TRACK_POINTS);
        assertEquals(834, track.points().size());
        assertEquals("2026-08-11T06:00:00", track.points().get(0).locatedAt());
        assertEquals("2026-08-11T08:46:36", track.points().get(track.points().size() - 1).locatedAt());
    }

    @Test
    void keepsSmallTrackIntact() {
        insertLocation(NOW.minusMinutes(3), 30.10, 120.70);
        insertLocation(NOW.minusMinutes(2), 30.11, 120.71);
        insertLocation(NOW.minusMinutes(1), 30.12, 120.72);

        RiderTrackDto track = riderTrackQueryService.track(RIDER_ID, null, null, null);

        assertEquals(3, track.points().size());
        assertEquals("2026-08-11", track.date());
        assertEquals(30.12, track.points().get(2).lat());
    }

    private void insertLocation(LocalDateTime locatedAt, double lat, double lng) {
        jdbcTemplate.update("""
                        INSERT INTO rider_location (rider_id, lat, lng, located_at, motion_state)
                        VALUES (?, ?, ?, ?, 'RIDING')
                        """,
                RIDER_ID, lat, lng, Timestamp.valueOf(locatedAt));
    }
}
