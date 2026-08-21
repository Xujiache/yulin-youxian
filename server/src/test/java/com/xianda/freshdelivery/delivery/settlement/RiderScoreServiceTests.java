package com.xianda.freshdelivery.delivery.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderScoreEvent;
import com.xianda.freshdelivery.delivery.exception.ExceptionRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.integration.NoopPushService;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.task.DeliveryConfigPort;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RiderScoreServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0, 0);

    private JdbcTemplate jdbcTemplate;
    private SettlementScoreEventDao scoreEventDao;
    private SettlementRiderDao riderDao;
    private RiderScoreService scoreService;
    private DeliveryConfigService configService;
    private MutableClock clock;
    private long riderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = A6Fixtures.database("a6_rider_score");
        A6Fixtures.setConfig(jdbcTemplate, DeliveryConfigPort.FAMILY_MODE, "false");
        clock = new MutableClock(NOW);
        scoreEventDao = new SettlementScoreEventDao(jdbcTemplate);
        riderDao = new SettlementRiderDao(jdbcTemplate);
        MessageService messageService = new MessageService(
                new MessageRecordDao(jdbcTemplate), new NoopPushService(), clock);
        configService = new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate));
        scoreService = new RiderScoreService(
                scoreEventDao, riderDao, new SettlementTaskQueryDao(jdbcTemplate),
                new ExceptionRecordDao(jdbcTemplate),
                configService, messageService, clock);
        riderId = A6Fixtures.insertRider(jdbcTemplate, "李四", 100);
    }

    @Test
    void onTimeDeliveryAddsOnePoint() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        scoreService.onTaskDelivered(taskId, true);
        assertEquals(101, currentScore());
        assertEquals(RiderScoreService.CODE_ON_TIME, latestEvent().eventCode());
    }

    @Test
    void overtimeDeliveryDeductsTwoPointsAndStaysRestorable() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, false));
        scoreService.onTaskDelivered(taskId, false);
        assertEquals(98, currentScore());
        RiderScoreEvent event = latestEvent();
        assertEquals(RiderScoreService.CODE_OVERTIME, event.eventCode());
        assertEquals(-2, event.scoreDelta().intValue());
        assertTrue(event.restorable());
        assertNull(event.restoredAt());
    }

    @Test
    void exemptExceptionSkipsOvertimePenaltyWithoutAppeal() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, false));
        insertExemptException(taskId);
        scoreService.onTaskDelivered(taskId, false);
        assertEquals(100, currentScore());
        assertEquals(0, scoreEventDao.countByRider(riderId));
    }

    @Test
    void exemptExceptionSkipsBadReviewPenalty() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        insertExemptException(taskId);
        scoreService.onRating(taskId, 1, false);
        assertEquals(100, currentScore());
        assertEquals(0, scoreEventDao.countByRider(riderId));
    }

    @Test
    void waivedBadReviewSkipsPenalty() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        scoreService.onRating(taskId, 2, true);
        assertEquals(100, currentScore());
        assertEquals(0, scoreEventDao.countByRider(riderId));
    }

    @Test
    void badReviewWithoutExemptionDeductsThree() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        scoreService.onRating(taskId, 1, false);
        assertEquals(97, currentScore());
        assertEquals(RiderScoreService.CODE_BAD_REVIEW, latestEvent().eventCode());
    }

    @Test
    void ratingIsRejectedBeforeTaskIsDelivered() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec(null, riderId, null, "DELIVERING", null, null, null, null,
                        null, 0, 0, null, null));

        assertThrows(DeliveryException.class, () -> scoreService.onRating(taskId, 5, false));
        assertEquals(100, currentScore());
        assertEquals(0, scoreEventDao.countByRider(riderId));
    }

    @Test
    void ratingAdapterCompensatesTrackingInsertForUndeliveredTask() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec(null, riderId, null, "DELIVERING", null, null, null, null,
                        null, 0, 0, null, null));
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM delivery_task WHERE id = ?", Long.class, taskId);
        jdbcTemplate.update("""
                INSERT INTO delivery_rating
                    (task_id, order_id, rider_id, user_id, star, is_negative)
                VALUES (?, ?, ?, 99, 5, 0)
                """, taskId, orderId, riderId);
        SettlementRatingAdapter adapter = new SettlementRatingAdapter(
                scoreService, new SettlementTaskQueryDao(jdbcTemplate), new SettlementRatingDao(jdbcTemplate));

        assertThrows(DeliveryException.class, () -> adapter.onRating(taskId, 5, false));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_rating WHERE task_id = ?", Integer.class, taskId));
    }

    @Test
    void familyModeSuppressesDeliveryRatingAndExceptionScoreEvents() {
        A6Fixtures.setConfig(jdbcTemplate, DeliveryConfigPort.FAMILY_MODE, "true");
        configService.invalidate();
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));

        scoreService.onTaskDelivered(taskId, true);
        scoreService.onRating(taskId, 5, false);
        scoreService.onExceptionRejected(riderId, taskId, "YC-FAMILY");

        assertEquals(100, currentScore());
        assertEquals(0, scoreEventDao.countByRider(riderId));
    }

    @Test
    void concurrentScoreEventsUseLockedAtomicUpdates() throws Exception {
        MessageService messageService = new MessageService(
                new MessageRecordDao(jdbcTemplate), new NoopPushService(), clock);
        RiderScoreService atomicService = new RiderScoreService(
                scoreEventDao, riderDao, new SettlementTaskQueryDao(jdbcTemplate),
                new ExceptionRecordDao(jdbcTemplate), configService, messageService, clock,
                TaskUnitOfWork.transactional(new TransactionTemplate(
                        new DataSourceTransactionManager(jdbcTemplate.getDataSource()))));
        long firstTask = A6Fixtures.insertTask(
                jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        long secondTask = A6Fixtures.insertTask(
                jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW.plusMinutes(1), true));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                start.await();
                atomicService.onTaskDelivered(firstTask, true);
                return null;
            });
            var second = pool.submit(() -> {
                start.await();
                atomicService.onTaskDelivered(secondTask, true);
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(102, currentScore());
        assertEquals(2, scoreEventDao.countByRider(riderId));
    }

    @Test
    void twentyConsecutiveOnTimeTasksRestoreFivePointsAndBackfillRestoredAt() {
        long overtimeTaskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, NOW.minusDays(2), false));
        scoreService.onTaskDelivered(overtimeTaskId, false);
        scoreService.onRating(overtimeTaskId, 1, false);
        assertEquals(100 - 2 - 3, currentScore());

        long lastTaskId = 0L;
        for (int i = 0; i < RiderScoreService.ON_TIME_STREAK_REQUIRED; i++) {
            clock.advance(Duration.ofMinutes(10));
            lastTaskId = A6Fixtures.insertTask(jdbcTemplate,
                    A6Fixtures.TaskSpec.delivered(riderId, NOW.plusMinutes(10L * i), true));
            if (i < RiderScoreService.ON_TIME_STREAK_REQUIRED - 1) {
                scoreService.onTaskDelivered(lastTaskId, true);
            }
        }
        int beforeRestore = currentScore();
        scoreService.onTaskDelivered(lastTaskId, true);

        assertEquals(beforeRestore + 1 + RiderScoreService.ON_TIME_STREAK_RESTORE, currentScore());
        RiderScoreEvent restore = latestEvent();
        assertEquals(RiderScoreService.CODE_RESTORE, restore.eventCode());
        assertEquals(RiderScoreService.ON_TIME_STREAK_RESTORE, restore.scoreDelta().intValue());
        List<RiderScoreEvent> stillRestorable = scoreEventDao.findRestorable(riderId);
        assertTrue(stillRestorable.isEmpty(), "被恢复的原事件应回填 restored_at");
        assertTrue(scoreEventDao.findByRider(riderId, 200, 0).stream()
                .filter(event -> RiderScoreService.CODE_OVERTIME.equals(event.eventCode()))
                .allMatch(event -> event.restoredAt() != null));
    }

    @Test
    void scoreIsClampedByConfiguredMaximum() {
        A6Fixtures.setConfig(jdbcTemplate, RiderScoreService.KEY_SCORE_MAX, "102");
        long firstTask = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        long secondTask = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        long thirdTask = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, true));
        scoreService.onTaskDelivered(firstTask, true);
        scoreService.onTaskDelivered(secondTask, true);
        scoreService.onTaskDelivered(thirdTask, true);
        assertEquals(102, currentScore());
    }

    @Test
    void manualAdjustRequiresOperatorAndReason() {
        assertThrows(DeliveryException.class, () -> scoreService.manualAdjust(riderId, -5, "", "王五"));
        assertThrows(DeliveryException.class, () -> scoreService.manualAdjust(riderId, -5, "违规", " "));
        assertNotNull(scoreService.manualAdjust(riderId, 3, "月度评优", "王五"));
        assertEquals(103, currentScore());
    }

    @Test
    void trainingRestoresTenPoints() {
        long overtimeTaskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, NOW, false));
        scoreService.onTaskDelivered(overtimeTaskId, false);
        assertEquals(98, currentScore());
        scoreService.onTrainingCompleted(riderId, "完成安全培训", "王五");
        assertEquals(98 + RiderScoreService.TRAINING_DELTA + 2, currentScore());
        assertTrue(scoreEventDao.findRestorable(riderId).isEmpty());
    }

    private void insertExemptException(long taskId) {
        jdbcTemplate.update("""
                        INSERT INTO delivery_exception
                            (exception_no, task_id, rider_id, exception_type, severity, status, source, rider_exempt)
                        VALUES (?, ?, ?, 'CUSTOMER_UNREACHABLE', 'NORMAL', 'RESOLVED', 'RIDER', 1)
                        """,
                "YC20260811" + String.format("%06d", taskId), taskId, riderId);
    }

    private int currentScore() {
        return riderDao.findById(riderId).orElseThrow().serviceScore();
    }

    private RiderScoreEvent latestEvent() {
        return scoreEventDao.findByRider(riderId, 1, 0).get(0);
    }
}
