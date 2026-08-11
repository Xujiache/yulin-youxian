package com.xianda.freshdelivery.delivery.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.dto.RiderSyncDto;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RiderSyncAndLivenessTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 40, 0);
    private static final long RIDER_ID = 1L;

    private JdbcTemplate jdbcTemplate;
    private TrackingTestSupport.MutableTestClock clock;
    private TrackingTestSupport.RecordingNotify notify;
    private RiderSyncService riderSyncService;
    private LivenessMonitor livenessMonitor;

    @BeforeEach
    void setUp() {
        jdbcTemplate = TrackingTestSupport.database("tracking_sync_liveness");
        clock = TrackingTestSupport.clock(NOW);
        notify = new TrackingTestSupport.RecordingNotify();
        TrackingPorts ports = new TrackingPorts(
                new TrackingTestSupport.FixedTrackingConfig(),
                notify,
                new JdbcTrackingPrivacyNumber(jdbcTemplate));
        TrackingRiderDao riderDao = new TrackingRiderDao(jdbcTemplate);
        riderSyncService = new RiderSyncService(new TrackingTaskDao(jdbcTemplate), riderDao, ports, clock);
        livenessMonitor = new LivenessMonitor(
                riderDao, new TrackingLocationDao(jdbcTemplate), ports, clock);

        TrackingTestSupport.insertRider(jdbcTemplate, RIDER_ID, "张三", NOW.minusDays(7));
        TrackingTestSupport.openShift(jdbcTemplate, 100L, RIDER_ID, NOW.minusHours(9));
    }

    @Test
    void syncReportsPendingTasksVersionAndUrgentMessages() {
        TrackingTestSupport.insertTask(jdbcTemplate, new TrackingTestSupport.TaskFixtureBuilder()
                .taskId(9001L).orderId(5001L).riderId(RIDER_ID).status("ASSIGNED")
                .destination(30.12, 120.76).updatedAt(NOW.minusMinutes(1)).build());
        jdbcTemplate.update("""
                INSERT INTO rider_message (rider_id, message_type, title, content, priority, need_voice, need_ack)
                VALUES (?, 'TASK_ASSIGNED', '新任务', '有一单新任务', 'URGENT', 1, 0)
                """, RIDER_ID);
        jdbcTemplate.update("""
                INSERT INTO rider_message (rider_id, message_type, title, content, priority, need_voice, need_ack)
                VALUES (?, 'ANNOUNCEMENT', '公告', '今日暴雨', 'NORMAL', 0, 0)
                """, RIDER_ID);

        RiderSyncDto sync = riderSyncService.sync(RIDER_ID);

        assertTrue(sync.hasNewTask());
        assertEquals(1, sync.pendingAcceptCount());
        assertEquals(NOW.minusMinutes(1).atZone(TrackingTimes.STORE_ZONE).toInstant().toEpochMilli(),
                sync.taskVersion());
        assertEquals(2, sync.unreadMessageCount());
        assertEquals(1, sync.urgentMessages().size());
        assertEquals("新任务", sync.urgentMessages().get(0).title());
        assertEquals(10, sync.config().reportIntervalSeconds());
        assertEquals(RiderSyncService.SYNC_INTERVAL_SECONDS, sync.config().syncIntervalSeconds());
        assertEquals("CONFIRM_8H", sync.fatigue().level());
        assertTrue(sync.fatigue().needConfirm());
        assertFalse(sync.fatigue().forceOffDuty());
    }

    @Test
    void alertsStaleRiderOnceAndClearsAfterRecovery() {
        TrackingTestSupport.insertLatest(jdbcTemplate, RIDER_ID, NOW.minusSeconds(300), 30.10, 120.70);

        assertEquals(1, livenessMonitor.evaluate());
        assertEquals(1, livenessMonitor.evaluate());
        assertEquals(1, notify.titles().size());
        assertEquals("定位已掉线", notify.titles().get(0));
        assertTrue(livenessMonitor.stale(RIDER_ID));

        jdbcTemplate.update("UPDATE rider_location_latest SET located_at = ? WHERE rider_id = ?",
                Timestamp.valueOf(NOW.minusSeconds(10)), RIDER_ID);

        assertEquals(0, livenessMonitor.evaluate());
        assertFalse(livenessMonitor.stale(RIDER_ID));

        jdbcTemplate.update("UPDATE rider_location_latest SET located_at = ? WHERE rider_id = ?",
                Timestamp.valueOf(NOW.minusSeconds(600)), RIDER_ID);

        assertEquals(1, livenessMonitor.evaluate());
        assertEquals(2, notify.titles().size());
    }

    @Test
    void treatsMissingLatestRowAsStale() {
        assertEquals(1, livenessMonitor.evaluate());
        assertTrue(livenessMonitor.staleRiderIds().contains(RIDER_ID));
    }
}
