package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.delivery.dto.WxTrackingDto;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WxTrackingPrivacyTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;
    private static final long OWN_ORDER_ID = 5001L;
    private static final long FOREIGN_ORDER_ID = 5002L;
    private static final double RIDER_LAT = 30.1200000;
    private static final double RIDER_LNG = 120.7600000;

    private JdbcTemplate jdbcTemplate;
    private WxTrackingService wxTrackingService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_wx_privacy");
        TrackingPorts ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                new TrackingTestSupport.RecordingNotify(),
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        wxTrackingService = new WxTrackingService(
                new TrackingTaskDao(jdbcTemplate),
                new TrackingRiderDao(jdbcTemplate),
                new LocationQueryService(new TrackingLocationDao(jdbcTemplate),
                        TrackingTestSupport.clock(NOW)),
                new TrackingTestSupport.FixedOrderAccess(OWN_ORDER_ID),
                new DeliveryEventStream(() -> 0L),
                ports,
                TrackingTestSupport.clock(NOW),
                TrackingTestSupport.urlSigner());

        TrackingTestSupport.insertRider(jdbcTemplate, RIDER_ID, "张伟", NOW.minusDays(7));
        TrackingTestSupport.insertLatest(jdbcTemplate, RIDER_ID, NOW.minusSeconds(10), RIDER_LAT, RIDER_LNG);
    }

    @Test
    void hidesRiderCardAndStopsPollingAfterTerminalStatus() {
        for (String terminalStatus : List.of("DELIVERED", "RETURNED", "CANCELLED")) {
            jdbcTemplate.update("DELETE FROM delivery_task");
            insertTask(terminalStatus, NOW.minusMinutes(20));

            WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

            assertTrue(tracking.hasDelivery());
            assertEquals(terminalStatus, tracking.taskStatus());
            assertNull(tracking.rider(), "终态任务必须整体隐藏骑手卡片: " + terminalStatus);
            assertNull(tracking.distanceMeters());
            assertTrue(tracking.polling().stopWhenDone());
        }
    }

    @Test
    void missingCompatibilityTaskKeepsPollingForAStillDeliveringStorefrontOrder() {
        WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

        assertEquals(false, tracking.hasDelivery());
        assertNotNull(tracking.polling());
        assertEquals(false, tracking.polling().stopWhenDone());
    }

    @Test
    void hidesRiderLocationBeforePickup() {
        insertTask("ASSIGNED", null);

        WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

        assertNotNull(tracking.rider());
        assertEquals("张师傅", tracking.rider().name());
        assertNull(tracking.rider().location(), "未取货前不得下发骑手位置");
        assertNull(tracking.rider().locatedAt());
        assertNull(tracking.rider().bearing());
        assertNull(tracking.distanceMeters());
    }

    @Test
    void returnsNotFoundWhenOrderBelongsToAnotherUser() {
        insertTask("DELIVERING", NOW.minusMinutes(10));
        jdbcTemplate.update("UPDATE delivery_task SET order_id = ?, order_no = 'DD00005002' WHERE id = 9001",
                FOREIGN_ORDER_ID);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> wxTrackingService.tracking(FOREIGN_ORDER_ID));

        assertEquals(404, exception.code());
    }

    @Test
    void neverReturnsRiderHistoryOnlyTheCurrentPoint() {
        insertTask("DELIVERING", NOW.minusMinutes(10));
        for (int index = 0; index < 30; index++) {
            TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID,
                    NOW.minusMinutes(30).plusSeconds(index * 10L), 30.11 + index * 0.0001, 120.75);
        }

        WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

        assertNotNull(tracking.rider());
        assertNotNull(tracking.rider().location());
        assertEquals(RIDER_LAT, tracking.rider().location().lat(), 0.0000001);
        assertEquals(RIDER_LNG, tracking.rider().location().lng(), 0.0000001);
        assertEquals(TrackingTimes.format(NOW.minusSeconds(10)), tracking.rider().locatedAt());
        assertTrue(tracking.rider().locationFresh());
        assertEquals(1, java.util.Arrays.stream(WxTrackingDto.TrackingRiderDto.class.getRecordComponents())
                .filter(component -> component.getName().equals("location"))
                .count());
        assertEquals(30, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class));
    }

    @Test
    void buildsFiveNodeTimelineWithEtaRangeAndStopsAhead() {
        TrackingTestSupport.insertWave(jdbcTemplate, 501L, RIDER_ID, NOW.toLocalDate());
        insertTask("DELIVERING", NOW.minusMinutes(10));
        jdbcTemplate.update("UPDATE delivery_task SET wave_id = 501 WHERE id = 9001");
        TrackingTestSupport.insertWaveStop(jdbcTemplate, 501L, 9001L, 2);
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9002L).orderId(5003L).waveId(501L).riderId(RIDER_ID).status("DELIVERING")
                .destination(30.1230000, 120.7650000).build());
        TrackingTestSupport.insertWaveStop(jdbcTemplate, 501L, 9002L, 1);

        WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

        assertEquals(5, tracking.timeline().size());
        assertEquals(List.of("ASSIGNED", "PICKED_UP", "DELIVERING", "ARRIVED", "DELIVERED"),
                tracking.timeline().stream().map(WxTrackingDto.TimelineNodeDto::code).toList());
        assertTrue(tracking.timeline().get(0).done());
        assertTrue(tracking.timeline().get(1).done());
        assertTrue(tracking.timeline().get(2).done());
        assertEquals(false, tracking.timeline().get(3).done());
        assertEquals(1, tracking.stopsAhead());
        assertTrue(tracking.eta().isRange());
        assertEquals("预计 15:45-15:55 送达", tracking.eta().displayText());
        assertEquals("骑手正在配送", tracking.taskStatusText());
        assertNotNull(tracking.distanceMeters());
        assertEquals("13800000001", tracking.rider().callNumber());
        assertTrue(tracking.rider().phoneDegraded(), "家庭模式返回真实号时必须明确标记降级");
    }

    private void insertTask(String status, LocalDateTime pickedUpAt) {
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9001L)
                .orderId(OWN_ORDER_ID)
                .riderId(RIDER_ID)
                .status(status)
                .destination(30.1234567, 120.7654321)
                .promisedAt(NOW.plusMinutes(20))
                .eta(NOW.plusMinutes(10), NOW.plusMinutes(5), NOW.plusMinutes(15))
                .assignedAt(NOW.minusMinutes(25))
                .pickedUpAt(pickedUpAt)
                .departedAt(pickedUpAt == null ? null : pickedUpAt.plusMinutes(1))
                .deliveredAt("DELIVERED".equals(status) ? NOW.minusMinutes(2) : null)
                .build());
    }
}
