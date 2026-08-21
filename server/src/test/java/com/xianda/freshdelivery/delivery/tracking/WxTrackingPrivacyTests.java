package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
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
    private TrackingPorts ports;
    private TrackingTaskDao taskDao;
    private LocationQueryService locationQueryService;
    private WxTrackingService wxTrackingService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_wx_privacy");
        ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                new TrackingTestSupport.RecordingNotify(),
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        taskDao = new TrackingTaskDao(jdbcTemplate);
        locationQueryService = new LocationQueryService(new TrackingLocationDao(jdbcTemplate),
                TrackingTestSupport.clock(NOW));
        wxTrackingService = trackingService(new CustomerLiveEtaService(taskDao, ports));

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
            assertNull(tracking.recentTrail());
            assertNull(tracking.remainingRoute());
            assertTrue(tracking.polling().stopWhenDone());
        }
    }

    @Test
    void missingCompatibilityTaskKeepsPollingForAStillDeliveringStorefrontOrder() {
        WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

        assertEquals(false, tracking.hasDelivery());
        assertNotNull(tracking.polling());
        assertEquals(false, tracking.polling().stopWhenDone());
        assertEquals(WxTrackingService.PRE_DEPART_POLLING_SECONDS, tracking.polling().intervalSeconds());
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
        assertNull(tracking.recentTrail());
        assertNull(tracking.remainingRoute());
        assertEquals(WxTrackingService.PRE_DEPART_POLLING_SECONDS, tracking.polling().intervalSeconds());
    }

    @Test
    void hidesRiderLocationAfterPickupBeforeDepart() {
        insertTask("PICKED_UP", NOW.minusMinutes(5), null);

        WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

        assertEquals("PICKED_UP", tracking.taskStatus());
        assertNotNull(tracking.rider());
        assertNull(tracking.rider().location(), "已取货但未发车不得下发骑手坐标");
        assertNull(tracking.recentTrail());
        assertNull(tracking.remainingRoute());
        assertNull(tracking.distanceMeters());
        assertEquals(WxTrackingService.PRE_DEPART_POLLING_SECONDS, tracking.polling().intervalSeconds());
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
    void returnsOnlyRecentCleanedTrailAfterDepart() {
        TrackingTestSupport.insertWave(jdbcTemplate, 501L, RIDER_ID, NOW.toLocalDate());
        insertTask("DELIVERING", NOW.minusMinutes(10));
        jdbcTemplate.update("UPDATE delivery_task SET wave_id = 501 WHERE id = 9001");
        TrackingTestSupport.insertWaveStop(jdbcTemplate, 501L, 9001L, 1);
        for (int index = 0; index < 30; index++) {
            TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, 501L,
                    NOW.minusMinutes(30).plusSeconds(index * 10L), 30.11 + index * 0.0001, 120.75, true);
        }
        TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, 501L,
                NOW.minusSeconds(90), 30.1180000, 120.7580000, true);
        TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, 501L,
                NOW.minusSeconds(40), 30.1190000, 120.7590000, false);
        TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, 501L,
                NOW.minusSeconds(20), 30.1195000, 120.7595000, true);
        TrackingTestSupport.insertLocation(jdbcTemplate, RIDER_ID, 999L,
                NOW.minusSeconds(15), 31.0000000, 121.0000000, true);

        WxTrackingDto tracking = wxTrackingService.tracking(OWN_ORDER_ID);

        assertNotNull(tracking.rider());
        assertNotNull(tracking.rider().location());
        assertEquals(RIDER_LAT, tracking.rider().location().lat(), 0.0000001);
        assertEquals(RIDER_LNG, tracking.rider().location().lng(), 0.0000001);
        assertEquals(TrackingTimes.format(NOW.minusSeconds(10)), tracking.rider().locatedAt());
        assertTrue(tracking.rider().locationFresh());
        assertEquals(WxTrackingService.POLLING_INTERVAL_SECONDS, tracking.polling().intervalSeconds());
        assertNotNull(tracking.recentTrail());
        assertEquals(2, tracking.recentTrail().size());
        assertEquals(30.1180000, tracking.recentTrail().get(0).lat(), 0.0000001);
        assertEquals(30.1195000, tracking.recentTrail().get(1).lat(), 0.0000001);
        assertTrue(tracking.recentTrail().stream().noneMatch(point -> point.lat() >= 31));
        assertEquals(1, java.util.Arrays.stream(WxTrackingDto.TrackingRiderDto.class.getRecordComponents())
                .filter(component -> component.getName().equals("location"))
                .count());
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
        assertEquals(CustomerLiveEtaService.SOURCE_LIVE, tracking.eta().source());
        assertEquals("骑手正在配送", tracking.taskStatusText());
        assertNotNull(tracking.distanceMeters());
        assertEquals("13800000001", tracking.rider().callNumber());
        assertTrue(tracking.rider().phoneDegraded(), "家庭模式返回真实号时必须明确标记降级");
        assertNotNull(tracking.remainingRoute());
        assertTrue(tracking.remainingRoute().points().size() >= 2);
        GeoPointDto last = tracking.remainingRoute().points().get(tracking.remainingRoute().points().size() - 1);
        assertEquals(30.1234567, last.lat(), 0.0000001);
        assertEquals(120.7654321, last.lng(), 0.0000001);

        jdbcTemplate.update("UPDATE delivery_task SET status = 'DELIVERED' WHERE id = 9002");
        WxTrackingDto afterPreviousDelivered = wxTrackingService.tracking(OWN_ORDER_ID);
        assertEquals(0, afterPreviousDelivered.stopsAhead());
    }

    @Test
    void liveEtaFallsBackToSnapshotWhenEstimatorIsUnavailable() {
        TrackingTestSupport.insertWave(jdbcTemplate, 501L, RIDER_ID, NOW.toLocalDate());
        insertTask("DELIVERING", NOW.minusMinutes(10));
        jdbcTemplate.update("UPDATE delivery_task SET wave_id = 501 WHERE id = 9001");
        TrackingTestSupport.insertWaveStop(jdbcTemplate, 501L, 9001L, 1);
        WxTrackingService snapshotOnly = trackingService(null);

        WxTrackingDto tracking = snapshotOnly.tracking(OWN_ORDER_ID);

        assertEquals(CustomerLiveEtaService.SOURCE_SNAPSHOT, tracking.eta().source());
        assertEquals(600, tracking.eta().remainingSeconds());
        assertEquals("预计 15:45-15:55 送达", tracking.eta().displayText());
        assertNull(tracking.remainingRoute());
        assertNotNull(tracking.rider().location());
    }

    private WxTrackingService trackingService(CustomerLiveEtaService liveEtaService) {
        return new WxTrackingService(
                taskDao,
                new TrackingRiderDao(jdbcTemplate),
                locationQueryService,
                new TrackingTestSupport.FixedOrderAccess(OWN_ORDER_ID),
                new DeliveryEventStream(() -> 0L),
                ports,
                TrackingTestSupport.clock(NOW),
                WxTrackingService.SUBSCRIBE_TEMPLATE_IDS,
                TrackingTestSupport.urlSigner(),
                liveEtaService);
    }

    private void insertTask(String status, LocalDateTime pickedUpAt) {
        insertTask(status, pickedUpAt, pickedUpAt == null ? null : pickedUpAt.plusMinutes(1));
    }

    private void insertTask(String status, LocalDateTime pickedUpAt, LocalDateTime departedAt) {
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
                .departedAt(departedAt)
                .deliveredAt("DELIVERED".equals(status) ? NOW.minusMinutes(2) : null)
                .build());
    }
}
