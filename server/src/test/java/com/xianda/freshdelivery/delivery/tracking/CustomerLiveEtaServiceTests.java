package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CustomerLiveEtaServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;

    private JdbcTemplate jdbcTemplate;
    private TrackingTaskDao taskDao;
    private CustomerLiveEtaService liveEtaService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_live_eta");
        TrackingPorts ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                new TrackingTestSupport.RecordingNotify(),
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        taskDao = new TrackingTaskDao(jdbcTemplate);
        liveEtaService = new CustomerLiveEtaService(taskDao, ports);
        TrackingTestSupport.insertRider(jdbcTemplate, RIDER_ID, "张伟", NOW.minusDays(7));
        TrackingTestSupport.insertWave(jdbcTemplate, 501L, RIDER_ID, NOW.toLocalDate());
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9001L).orderId(5001L).waveId(501L).riderId(RIDER_ID).status("DELIVERING")
                .destination(30.1234567, 120.7654321)
                .pickedUpAt(NOW.minusMinutes(10))
                .departedAt(NOW.minusMinutes(9))
                .build());
        TrackingTestSupport.insertWaveStop(jdbcTemplate, 501L, 9001L, 2);
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9002L).orderId(5003L).waveId(501L).riderId(RIDER_ID).status("DELIVERING")
                .destination(30.1210000, 120.7620000).build());
        TrackingTestSupport.insertWaveStop(jdbcTemplate, 501L, 9002L, 1);
    }

    @Test
    void estimatesFromLatestPointUntilCurrentStopAndInvalidatesWhenPreviousCompletes() {
        TrackingLocationDao.LatestRow origin = new TrackingLocationDao.LatestRow(
                RIDER_ID, 30.1200000, 120.7600000, 10, 4.2, 178.5, 68, "RIDING", 501L, 9002L, NOW);
        TrackingTaskDao.WxTaskRow task = taskDao.findByOrderId(5001L).orElseThrow();

        Optional<CustomerLiveEtaService.LiveEstimate> first = liveEtaService.estimate(task, origin, NOW);
        assertTrue(first.isPresent());
        assertTrue(first.get().remainingSeconds() > 0);
        assertEquals(first.get().remainingSeconds(),
                liveEtaService.estimate(task, origin, NOW.plusSeconds(10)).orElseThrow().remainingSeconds(),
                "同一指纹在节流窗口内应复用");

        jdbcTemplate.update("UPDATE delivery_task SET status = 'DELIVERED' WHERE id = 9002");
        CustomerLiveEtaService.LiveEstimate after = liveEtaService.estimate(task, origin, NOW.plusSeconds(11))
                .orElseThrow();
        assertTrue(after.remainingSeconds() < first.get().remainingSeconds());
        assertEquals(30.1234567,
                after.remainingRoute().points().get(after.remainingRoute().points().size() - 1).lat(), 0.0000001);
    }
}
