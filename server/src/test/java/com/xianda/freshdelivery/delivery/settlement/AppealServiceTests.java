package com.xianda.freshdelivery.delivery.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.RiderScoreEvent;
import com.xianda.freshdelivery.delivery.dto.AppealCreateRequest;
import com.xianda.freshdelivery.delivery.dto.AppealDto;
import com.xianda.freshdelivery.delivery.dto.AppealReviewRequest;
import com.xianda.freshdelivery.delivery.exception.ExceptionRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.integration.NoopPushService;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import com.xianda.freshdelivery.delivery.task.DeliveryConfigPort;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AppealServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 9, 30, 0);

    private JdbcTemplate jdbcTemplate;
    private SettlementScoreEventDao scoreEventDao;
    private SettlementRiderDao riderDao;
    private RiderScoreService scoreService;
    private AppealService appealService;
    private long riderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = A6Fixtures.database("a6_appeal_service");
        A6Fixtures.setConfig(jdbcTemplate, DeliveryConfigPort.FAMILY_MODE, "false");
        MutableClock clock = new MutableClock(NOW);
        scoreEventDao = new SettlementScoreEventDao(jdbcTemplate);
        riderDao = new SettlementRiderDao(jdbcTemplate);
        MessageService messageService = new MessageService(
                new MessageRecordDao(jdbcTemplate), new NoopPushService(), clock);
        scoreService = new RiderScoreService(scoreEventDao, riderDao, new SettlementTaskQueryDao(jdbcTemplate),
                new ExceptionRecordDao(jdbcTemplate),
                new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate)), messageService, clock);
        appealService = new AppealService(new SettlementAppealDao(jdbcTemplate), scoreService, messageService, clock);
        riderId = A6Fixtures.insertRider(jdbcTemplate, "蒋十六", 100);
    }

    @Test
    void appealNumberFollowsSsDatePattern() {
        AppealDto first = submit(manualPenalty("第一次"));
        assertEquals("SS202608110001", first.appealNo());
        assertEquals(AppealService.STATUS_PENDING, first.status());
        assertEquals("SS202608110002", submit(manualPenalty("第二次")).appealNo());
    }

    @Test
    void approvedScoreEventAppealRestoresThePoints() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, false));
        scoreService.onTaskDelivered(taskId, false);
        assertEquals(98, currentScore());
        RiderScoreEvent penalty = scoreEventDao.findByRider(riderId, 1, 0).get(0);

        AppealDto appeal = submit(penalty.id());
        AppealDto reviewed = appealService.review(appeal.id(), new AppealReviewRequest(true, "确属系统误判"), "申诉专员");

        assertEquals(AppealService.STATUS_APPROVED, reviewed.status());
        assertEquals("申诉专员", reviewed.reviewedBy());
        assertEquals(100, currentScore());
        assertNotNull(scoreEventDao.findById(penalty.id()).orElseThrow().restoredAt());
        assertTrue(scoreEventDao.findByRider(riderId, 10, 0).stream()
                .anyMatch(event -> RiderScoreService.CODE_RESTORE.equals(event.eventCode())));
    }

    @Test
    void rejectedAppealKeepsScoreUnchanged() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOW, false));
        scoreService.onTaskDelivered(taskId, false);
        RiderScoreEvent penalty = scoreEventDao.findByRider(riderId, 1, 0).get(0);

        AppealDto appeal = submit(penalty.id());
        appealService.review(appeal.id(), new AppealReviewRequest(false, "证据不足"), "申诉专员");

        assertEquals(98, currentScore());
        assertNull(scoreEventDao.findById(penalty.id()).orElseThrow().restoredAt());
    }

    @Test
    void appealCanOnlyBeReviewedOnce() {
        AppealDto appeal = submit(manualPenalty("重复审核"));
        appealService.review(appeal.id(), new AppealReviewRequest(false, "证据不足"), "申诉专员");
        assertThrows(DeliveryException.class, () ->
                appealService.review(appeal.id(), new AppealReviewRequest(true, "改判"), "申诉专员"));
    }

    @Test
    void invalidSubmissionsAreRejected() {
        assertThrows(DeliveryException.class, () -> appealService.submit(riderId,
                new AppealCreateRequest("UNKNOWN", 1L, "理由", List.of())));
        assertThrows(DeliveryException.class, () -> appealService.submit(riderId,
                new AppealCreateRequest(AppealService.TARGET_SCORE_EVENT, null, "理由", List.of())));
        assertThrows(DeliveryException.class, () -> appealService.submit(riderId,
                new AppealCreateRequest(AppealService.TARGET_SCORE_EVENT, 1L, " ", List.of())));
    }

    @Test
    void riderCannotAppealAnotherRidersScoreEvent() {
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "其他骑手", 100);
        long otherEventId = scoreService.manualAdjust(otherRiderId, -2, "其他骑手事件", "管理员").id();

        assertThrows(DeliveryException.class, () -> appealService.submit(
                riderId,
                new AppealCreateRequest(
                        AppealService.TARGET_SCORE_EVENT, otherEventId, "越权申诉", List.of())));
    }

    @Test
    void failedScoreRestoreRollsAppealReviewBackToPending() {
        long penaltyId = manualPenalty("回滚");
        RiderScoreService failingScoreService = mock(RiderScoreService.class);
        when(failingScoreService.restoreScoreEvent(penaltyId, "申诉专员"))
                .thenThrow(new IllegalStateException("模拟服务分回写失败"));
        SettlementAppealDao appealDao = new SettlementAppealDao(jdbcTemplate);
        TaskUnitOfWork unitOfWork = TaskUnitOfWork.transactional(new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource())));
        AppealService atomicService = new AppealService(
                appealDao, failingScoreService,
                new MessageService(new MessageRecordDao(jdbcTemplate), new NoopPushService(),
                        new MutableClock(NOW)),
                new MutableClock(NOW), unitOfWork);
        AppealDto appeal = atomicService.submit(riderId, new AppealCreateRequest(
                AppealService.TARGET_SCORE_EVENT, penaltyId, "申请复核", List.of()));

        assertThrows(IllegalStateException.class, () -> atomicService.review(
                appeal.id(), new AppealReviewRequest(true, "应恢复"), "申诉专员"));

        assertEquals(AppealService.STATUS_PENDING, appealDao.findById(appeal.id()).orElseThrow().status());
    }

    private AppealDto submit(long targetId) {
        return appealService.submit(riderId, new AppealCreateRequest(
                AppealService.TARGET_SCORE_EVENT, targetId, "当时电梯停运，属客观原因", List.of()));
    }

    private long manualPenalty(String suffix) {
        return scoreService.manualAdjust(riderId, -1, "测试扣分-" + suffix, "管理员").id();
    }

    private int currentScore() {
        return riderDao.findById(riderId).orElseThrow().serviceScore();
    }
}
