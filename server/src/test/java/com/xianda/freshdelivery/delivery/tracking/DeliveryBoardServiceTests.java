package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.dto.DeliveryBoardDto;
import com.xianda.freshdelivery.delivery.dto.DeliveryMapDto;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryBoardServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;

    private JdbcTemplate jdbcTemplate;
    private DeliveryBoardService boardService;
    private DeliveryHealthService healthService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_board");
        TrackingPorts ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                new TrackingTestSupport.RecordingNotify(),
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        TrackingBoardDao boardDao = new TrackingBoardDao(jdbcTemplate);
        TrackingRiderDao riderDao = new TrackingRiderDao(jdbcTemplate);
        TrackingLocationDao locationDao = new TrackingLocationDao(jdbcTemplate);
        LocationQueryService locationQueryService =
                new LocationQueryService(locationDao, TrackingTestSupport.clock(NOW));
        LivenessMonitor livenessMonitor =
                new LivenessMonitor(riderDao, locationDao, ports, TrackingTestSupport.clock(NOW));
        boardService = new DeliveryBoardService(
                boardDao, riderDao, locationQueryService, livenessMonitor, ports,
                TrackingTestSupport.clock(NOW));
        healthService = new DeliveryHealthService(
                boardService, boardDao, locationDao, livenessMonitor,
                new TrackingIngestMetrics(() -> 0L), new TrackingTestSupport.StubBackupPort(), ports,
                TrackingTestSupport.clock(NOW));

        TrackingTestSupport.insertRider(jdbcTemplate, RIDER_ID, "张三", NOW.minusDays(7));
        TrackingTestSupport.openShift(jdbcTemplate, 100L, RIDER_ID, NOW.minusHours(5));
        TrackingTestSupport.insertLatest(jdbcTemplate, RIDER_ID, NOW.minusSeconds(20), 30.1100000, 120.7100000);
    }

    @Test
    void buildsFourQueuesAndRiderCards() {
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9001L).orderId(5001L).status("PENDING").destination(30.12, 120.76)
                .promisedAt(NOW.plusHours(3)).build());
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9002L).orderId(5002L).riderId(RIDER_ID).status("DELIVERING")
                .destination(30.1250000, 120.7650000).promisedAt(NOW.plusMinutes(5)).build());
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9003L).orderId(5003L).riderId(RIDER_ID).status("EXCEPTION")
                .destination(30.1260000, 120.7660000).promisedAt(NOW.plusHours(2)).build());

        DeliveryBoardDto board = boardService.board();

        assertEquals(1, board.queues().pending().size());
        assertEquals(1, board.queues().overtimeRisk().size());
        assertEquals(1, board.queues().openException().size());
        assertEquals(DeliveryBoardService.RISK_HIGH, board.queues().overtimeRisk().get(0).overtimeRisk());
        assertEquals(1, board.riders().size());
        assertEquals(1, board.summary().pendingCount());
        assertEquals(1, board.summary().deliveringCount());
        assertEquals(1, board.summary().onDutyRiderCount());
        assertEquals(2, board.riders().get(0).currentTaskCount());
        assertNotNull(board.riders().get(0).location());
        assertEquals(false, board.riders().get(0).locationStale());
        assertEquals("WARN_4H", board.riders().get(0).fatigueLevel());
        assertNotNull(board.queues().overtimeRisk().get(0).distanceFromRiderMeters());
        assertTrue(board.queues().idleRiders().isEmpty());
    }

    @Test
    void raisesCapacityWarningWhenBacklogExceedsTwiceTheOnDutyRiders() {
        for (int index = 0; index < 3; index++) {
            TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                    .taskId(9100L + index).orderId(5100L + index).status("PENDING")
                    .destination(30.12, 120.76).promisedAt(NOW.plusHours(3)).build());
        }

        DeliveryBoardDto board = boardService.board();

        assertEquals(3, board.summary().pendingCount());
        assertTrue(board.summary().capacityWarning());
        assertEquals(1, board.queues().idleRiders().size());
    }

    @Test
    void todaySummaryCountsDeliveriesByDeliveredAtNotByCustomerDeliveryDate() {
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9301L).orderId(5301L).riderId(RIDER_ID).status("DELIVERED")
                .deliveryDate(NOW.toLocalDate().plusDays(1))
                .pickedUpAt(NOW.minusMinutes(50)).deliveredAt(NOW.minusMinutes(20))
                .destination(30.12, 120.76).build());
        jdbcTemplate.update("UPDATE delivery_task SET is_on_time = 1 WHERE id = 9301");

        DeliveryBoardDto board = boardService.board();

        assertEquals(30, board.summary().avgDeliveryMinutes());
        assertEquals(1.0d, board.summary().onTimeRateToday());
    }

    @Test
    void todaySummaryIgnoresDeliveriesFromOtherDays() {
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9302L).orderId(5302L).riderId(RIDER_ID).status("DELIVERED")
                .deliveryDate(NOW.toLocalDate())
                .pickedUpAt(NOW.minusDays(1).minusMinutes(50)).deliveredAt(NOW.minusDays(1))
                .destination(30.12, 120.76).build());

        DeliveryBoardDto board = boardService.board();

        assertEquals(0, board.summary().avgDeliveryMinutes());
        assertNull(board.summary().onTimeRateToday());
    }

    @Test
    void mapEndpointReturnsCoordinatesOnly() {
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9001L).orderId(5001L).status("PENDING").destination(30.12, 120.76).build());

        DeliveryMapDto map = boardService.map();

        assertEquals(1, map.riders().size());
        assertEquals(1, map.tasks().size());
        assertEquals(RIDER_ID, map.riders().get(0).riderId());
        assertEquals(30.12, map.tasks().get(0).lat(), 0.0000001);
    }

    @Test
    void healthReportsUnknownDispatchLoopAndDegradedPrivacyNumber() {
        DeliveryHealthDto health = healthService.health();

        assertEquals(DeliveryHealthService.STATUS_UNKNOWN, health.dispatchLoop().status());
        assertEquals(DeliveryHealthService.STATUS_DEGRADED, health.external().privacyNumber());
        assertEquals(DeliveryHealthService.STATUS_DISABLED, health.external().amap());
        assertEquals(DeliveryHealthService.STATUS_NOOP, health.external().push());
        assertEquals(1, health.riders().onDuty());
        assertEquals(0, health.tasks().pending());
        assertEquals(0d, health.location().rejectRate(), 0.0001);
        assertEquals("2026-08-11T03:00:00", health.backup().lastAt());
        assertTrue(health.backup().deliveryTablesIncluded());
    }
}
