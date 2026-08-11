package com.xianda.freshdelivery.delivery.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.domain.DeliveryExceptionRecord;
import com.xianda.freshdelivery.delivery.dto.ExceptionCreateRequest;
import com.xianda.freshdelivery.delivery.dto.ExceptionDto;
import com.xianda.freshdelivery.delivery.dto.ExceptionHandleRequest;
import com.xianda.freshdelivery.delivery.dto.GeoPointDto;
import com.xianda.freshdelivery.delivery.integration.MessageRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.integration.NoopPushService;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.routing.EtaEngine;
import com.xianda.freshdelivery.delivery.settlement.A6Fixtures;
import com.xianda.freshdelivery.delivery.settlement.RiderScoreService;
import com.xianda.freshdelivery.delivery.settlement.SettlementRiderDao;
import com.xianda.freshdelivery.delivery.settlement.SettlementScoreEventDao;
import com.xianda.freshdelivery.delivery.settlement.SettlementTaskQueryDao;
import com.xianda.freshdelivery.delivery.task.DeliveryTaskService;
import com.xianda.freshdelivery.delivery.task.DeliveryConfigPort;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DeliveryExceptionServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 15, 42, 0);

    private JdbcTemplate jdbcTemplate;
    private ExceptionRecordDao exceptionRecordDao;
    private ExceptionBuildingStatDao buildingStatDao;
    private DeliveryTaskService taskService;
    private EtaEngine etaEngine;
    private DeliveryExceptionService exceptionService;
    private MessageService messageService;
    private RiderScoreService scoreService;
    private MutableClock clock;
    private long riderId;
    private long taskId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = A6Fixtures.database("a6_exception_service");
        A6Fixtures.setConfig(jdbcTemplate, DeliveryConfigPort.FAMILY_MODE, "false");
        clock = new MutableClock(NOW);
        exceptionRecordDao = new ExceptionRecordDao(jdbcTemplate);
        buildingStatDao = new ExceptionBuildingStatDao(jdbcTemplate);
        taskService = mock(DeliveryTaskService.class);
        etaEngine = mock(EtaEngine.class);
        messageService = new MessageService(
                new MessageRecordDao(jdbcTemplate), new NoopPushService(), clock);
        DeliveryConfigService configService = new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate));
        scoreService = new RiderScoreService(
                new SettlementScoreEventDao(jdbcTemplate), new SettlementRiderDao(jdbcTemplate),
                new SettlementTaskQueryDao(jdbcTemplate), exceptionRecordDao, configService, messageService, clock);
        exceptionService = new DeliveryExceptionService(
                exceptionRecordDao, new ExceptionEvidenceDao(jdbcTemplate), new ExceptionTaskQueryDao(jdbcTemplate),
                buildingStatDao, taskService, messageService, scoreService,
                A6Fixtures.provider(etaEngine), clock);
        riderId = A6Fixtures.insertRider(jdbcTemplate, "赵六", 100);
        taskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec(null, riderId, null, "DELIVERING", "GK-1", "阳光小区", "3 栋", 5,
                        null, 1200, 1200, null, null));
    }

    @Test
    void exceptionNumberFollowsYcDatePattern() {
        ExceptionDto dto = report("GOODS_DAMAGED");
        assertNotNull(dto.exceptionNo());
        assertEquals("YC20260811000001", dto.exceptionNo());
        assertEquals("OPEN", dto.status());
        assertTrue(dto.riderExempt());
        assertEquals("YC20260811000002", report("GOODS_DAMAGED").exceptionNo());
    }

    @Test
    void unreachableCustomerHoldsForThirtyMinutesAndCarriesGuidance() {
        ExceptionDto dto = report("CUSTOMER_UNREACHABLE");
        assertEquals("2026-08-11T16:12:00", dto.holdUntilAt());
        assertTrue(dto.guidance().contains("挂起 30 分钟"), dto.guidance());
        assertEquals(List.of("CONTINUE", "RETURN", "REASSIGN"), dto.allowedNextActions());
        verify(taskService).enterException(eq(taskId), anyLong());
    }

    @Test
    void expiredHoldsAreReleasedByScan() {
        ExceptionDto dto = report("CUSTOMER_UNREACHABLE");
        assertEquals(0, exceptionService.releaseExpiredHolds());
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertEquals(1, exceptionService.releaseExpiredHolds());
        assertEquals(DeliveryExceptionService.STATUS_PROCESSING,
                exceptionRecordDao.findById(dto.exceptionId()).orElseThrow().status());
        assertEquals(0, exceptionService.releaseExpiredHolds());
    }

    @Test
    void storeSlowTriggersCascadePickingDelay() {
        report("STORE_SLOW");
        verify(etaEngine).applyPickingDelay(taskId, DeliveryExceptionService.STORE_SLOW_DELAY_SECONDS);
    }

    @Test
    void accessDeniedRecomputesBuildingDifficultyOnRollingWindow() {
        report("ACCESS_DENIED");
        assertEquals(1, buildingStatDao.findAccessDifficulty("GK-1").intValue());
        report("ACCESS_DENIED");
        assertEquals(2, buildingStatDao.findAccessDifficulty("GK-1").intValue());

        jdbcTemplate.update("UPDATE delivery_exception SET created_at = ? WHERE exception_type = 'ACCESS_DENIED'",
                java.sql.Timestamp.valueOf(NOW.minusDays(120)));
        report("ACCESS_DENIED");
        assertEquals(1, buildingStatDao.findAccessDifficulty("GK-1").intValue(),
                "90 天滚动窗口应重算而不是累加");
    }

    @Test
    void handleContinueResolvesExceptionAndAwardsScore() {
        ExceptionDto reported = report("GOODS_DAMAGED");
        ExceptionDto handled = exceptionService.handle(reported.exceptionId(),
                new ExceptionHandleRequest("CONTINUE", "已安抚顾客", true), null, "调度员甲");
        assertEquals(DeliveryExceptionService.STATUS_RESOLVED, handled.status());
        assertEquals("CONTINUE", handled.resolutionType());
        verify(taskService).resolveException(taskId, "CONTINUE", "已安抚顾客", "调度员甲");
        assertEquals(101, riderScore());
    }

    @Test
    void handleIgnoreClosesExceptionWithoutTaskStatusChange() {
        ExceptionDto reported = report("OTHER");
        ExceptionDto handled = exceptionService.handle(reported.exceptionId(),
                new ExceptionHandleRequest("IGNORE", "重复上报", true), null, "调度员甲");
        assertEquals(DeliveryExceptionService.STATUS_CLOSED, handled.status());
    }

    @Test
    void rejectedExceptionDeductsThreePoints() {
        ExceptionDto reported = report("OTHER");
        exceptionService.handle(reported.exceptionId(),
                new ExceptionHandleRequest("IGNORE", "上报不实", false), null, "调度员甲");
        assertEquals(97, riderScore());
        assertFalse(exceptionRecordDao.hasExemptException(taskId));
    }

    @Test
    void reassignRequiresTargetRider() {
        ExceptionDto reported = report("VEHICLE_FAILURE");
        assertThrows(DeliveryException.class, () -> exceptionService.handle(reported.exceptionId(),
                new ExceptionHandleRequest("REASSIGN", "车辆故障", true), null, "调度员甲"));
        verify(taskService, never()).reassignTask(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void reassignCallsTaskServiceWithTargetRider() {
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "钱七", 100);
        ExceptionDto reported = report("VEHICLE_FAILURE");
        exceptionService.handle(reported.exceptionId(),
                new ExceptionHandleRequest("REASSIGN", "车辆故障", true), otherRiderId, "调度员甲");
        verify(taskService).reassignTask(eq(taskId), eq(otherRiderId), anyString(), eq("ADMIN"), eq("调度员甲"));
    }

    @Test
    void riderCannotReportExceptionForAnotherRidersTask() {
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "越权骑手", 100);
        long otherTaskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec(null, otherRiderId, null, "DELIVERING", null, null, null, null,
                        null, 0, 0, null, null));
        long before = exceptionRecordDao.countByRider(riderId, null);

        assertThrows(DeliveryException.class, () -> exceptionService.report(
                riderId,
                new ExceptionCreateRequest("cross-rider", null, otherTaskId, "OTHER",
                        "越权上报", List.of(), null)));

        assertEquals(before, exceptionRecordDao.countByRider(riderId, null));
        verify(taskService, never()).enterException(eq(otherTaskId), anyLong());
    }

    @Test
    void exceptionCannotBindEvidenceFromAnotherTask() {
        long otherTaskId = A6Fixtures.insertTask(jdbcTemplate,
                new A6Fixtures.TaskSpec(null, riderId, null, "DELIVERING", null, null, null, null,
                        null, 0, 0, null, null));
        jdbcTemplate.update("""
                INSERT INTO delivery_evidence
                    (task_id, rider_id, evidence_type, file_url, captured_at)
                VALUES (?, ?, 'EXCEPTION', '/uploads/delivery/cross.jpg', ?)
                """, otherTaskId, riderId, java.sql.Timestamp.valueOf(NOW));
        Long evidenceId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM delivery_evidence", Long.class);

        assertThrows(DeliveryException.class, () -> exceptionService.report(
                riderId,
                new ExceptionCreateRequest("cross-evidence", null, taskId, "OTHER",
                        "尝试绑错任务凭证", List.of(evidenceId), null)));
        assertEquals(0, exceptionRecordDao.countByRider(riderId, null));
    }

    @Test
    void refundRemainsProcessingUntilPaymentPortReportsSuccess() {
        DeliveryRefundPort refundPort = mock(DeliveryRefundPort.class);
        when(refundPort.requestFullRefund(anyLong(), anyString(), anyString()))
                .thenReturn(new DeliveryRefundPort.RefundResult(71L, "TK71", "退款中"))
                .thenReturn(new DeliveryRefundPort.RefundResult(71L, "TK71", "退款成功"));
        exceptionService = new DeliveryExceptionService(
                exceptionRecordDao, new ExceptionEvidenceDao(jdbcTemplate), new ExceptionTaskQueryDao(jdbcTemplate),
                buildingStatDao, taskService, messageService, scoreService,
                A6Fixtures.provider(etaEngine), refundPort, TaskUnitOfWork.direct(), clock);
        ExceptionDto reported = report("CUSTOMER_REFUSED");

        ExceptionDto processing = exceptionService.handle(
                reported.exceptionId(), new ExceptionHandleRequest("REFUND", "顾客拒收全额退款", true),
                null, "调度员甲");

        assertEquals(DeliveryExceptionService.STATUS_PROCESSING, processing.status());
        assertEquals("REFUND", processing.resolutionType());
        verify(taskService, never()).resolveException(eq(taskId), eq("REFUND"), anyString(), anyString());

        assertEquals(1, exceptionService.retryPendingRefunds());
        assertEquals(DeliveryExceptionService.STATUS_RESOLVED,
                exceptionRecordDao.findById(reported.exceptionId()).orElseThrow().status());
        verify(taskService).resolveException(eq(taskId), eq("REFUND"), anyString(), anyString());
    }

    @Test
    void unknownExceptionTypeIsRejected() {
        assertThrows(DeliveryException.class, () -> report("NOT_A_TYPE"));
    }

    @Test
    void severityIsDerivedFromExceptionType() {
        assertEquals("URGENT", record(report("RIDER_UNWELL")).severity());
        assertEquals("HIGH", record(report("GOODS_LEAKING")).severity());
        assertEquals("NORMAL", record(report("WRONG_ADDRESS")).severity());
    }

    @Test
    void riderCanOnlySeeOwnException() {
        ExceptionDto reported = report("OTHER");
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "孙八", 100);
        assertThrows(DeliveryException.class, () -> exceptionService.riderDetail(otherRiderId, reported.exceptionId()));
        assertNotNull(exceptionService.riderDetail(riderId, reported.exceptionId()));
    }

    @Test
    void reportWithoutHoldHasNoHoldUntil() {
        assertNull(report("WRONG_ADDRESS").holdUntilAt());
    }

    private ExceptionDto report(String type) {
        return exceptionService.report(riderId, new ExceptionCreateRequest(
                "client-" + type + "-" + System.nanoTime(), null, taskId, type, "测试上报",
                List.of(), new GeoPointDto(30.1234567d, 120.7654321d)));
    }

    private DeliveryExceptionRecord record(ExceptionDto dto) {
        return exceptionRecordDao.findById(dto.exceptionId()).orElseThrow();
    }

    private int riderScore() {
        return new SettlementRiderDao(jdbcTemplate).findById(riderId).orElseThrow().serviceScore();
    }
}
