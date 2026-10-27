package com.xianda.freshdelivery.delivery.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.dto.AdminSettlementDto;
import com.xianda.freshdelivery.delivery.dto.EarningSummaryDto;
import com.xianda.freshdelivery.delivery.dto.SettlementAdjustRequest;
import com.xianda.freshdelivery.delivery.dto.SettlementDto;
import com.xianda.freshdelivery.delivery.integration.DeliveryWeatherConditionPort;
import com.xianda.freshdelivery.delivery.integration.MessageRecordDao;
import com.xianda.freshdelivery.delivery.integration.MessageService;
import com.xianda.freshdelivery.delivery.integration.NoopPushService;
import com.xianda.freshdelivery.delivery.integration.NoopWeatherService;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.task.DeliveryConfigPort;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SettlementServiceTests {
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 8, 10);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 1, 0, 0);

    private JdbcTemplate jdbcTemplate;
    private SettlementService settlementService;
    private SettlementItemDao settlementItemDao;
    private EarningRuleEngine earningRuleEngine;
    private long riderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = A6Fixtures.database("a6_settlement_service");
        A6Fixtures.setConfig(jdbcTemplate, DeliveryConfigPort.FAMILY_MODE, "false");
        MutableClock clock = new MutableClock(NOW);
        settlementItemDao = new SettlementItemDao(jdbcTemplate);
        SettlementTaskQueryDao taskQueryDao = new SettlementTaskQueryDao(jdbcTemplate);
        MessageService messageService = new MessageService(
                new MessageRecordDao(jdbcTemplate), new NoopPushService(), clock);
        earningRuleEngine = new EarningRuleEngine(taskQueryDao, settlementItemDao,
                new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate)), new HolidayCalendar(),
                A6Fixtures.provider(new DeliveryWeatherConditionPort(new NoopWeatherService(), null)), clock);
        settlementService = new SettlementService(new SettlementDao(jdbcTemplate), settlementItemDao,
                taskQueryDao, new SettlementRiderDao(jdbcTemplate), messageService, clock);
        riderId = A6Fixtures.insertRider(jdbcTemplate, "吴十", 100);
    }

    @Test
    void dailySettlementAggregatesYesterdayItemsAndIsIdempotent() {
        settleTask(LocalDateTime.of(2026, 8, 10, 10, 0), 2400, true);
        settleTask(LocalDateTime.of(2026, 8, 10, 21, 0), 0, false);

        List<Long> first = settlementService.generate(SettlementService.PERIOD_DAILY, YESTERDAY, YESTERDAY, null);
        assertEquals(1, first.size());
        AdminSettlementDto settlement = settlementService.detail(first.get(0));
        assertEquals(600, settlement.baseAmount());
        assertEquals(70, settlement.distanceAmount());
        assertEquals(100, settlement.nightAmount());
        assertEquals(300 + 70 + 300 + 100, settlement.totalAmount());
        assertEquals(2, settlement.taskCount());
        assertEquals(1, settlement.onTimeCount());
        assertEquals(SettlementService.STATUS_DRAFT, settlement.status());
        assertTrue(settlement.settlementNo().startsWith("JS20260810"));

        List<Long> second = settlementService.generate(
                SettlementService.PERIOD_DAILY, YESTERDAY, YESTERDAY, List.of(riderId));
        assertTrue(second.isEmpty(), "同周期不得重复生成结算单");
        assertEquals(1L, settlementService.search(riderId, null, null, 1, 20).total());
    }

    @Test
    void lifecycleGoesDraftConfirmedPaid() {
        settleTask(LocalDateTime.of(2026, 8, 10, 10, 0), 0, true);
        long settlementId = settlementService
                .generate(SettlementService.PERIOD_DAILY, YESTERDAY, YESTERDAY, null).get(0);

        assertThrows(DeliveryException.class, () -> settlementService.pay(settlementId, "财务甲"));
        SettlementDto confirmed = settlementService.confirm(settlementId, "财务甲");
        assertEquals(SettlementService.STATUS_CONFIRMED, confirmed.status());
        SettlementDto paid = settlementService.pay(settlementId, "财务甲");
        assertEquals(SettlementService.STATUS_PAID, paid.status());
        assertThrows(DeliveryException.class, () -> settlementService.voidSettlement(settlementId, "财务甲"));
    }

    @Test
    void adjustRequiresOperatorAndRemarkAndIsTheOnlyNegativePath() {
        settleTask(LocalDateTime.of(2026, 8, 10, 10, 0), 0, true);
        long settlementId = settlementService
                .generate(SettlementService.PERIOD_DAILY, YESTERDAY, YESTERDAY, null).get(0);

        assertThrows(DeliveryException.class, () ->
                settlementService.adjust(settlementId, new SettlementAdjustRequest(-100, "少发"), " "));
        assertThrows(DeliveryException.class, () ->
                settlementService.adjust(settlementId, new SettlementAdjustRequest(-100, " "), "财务甲"));

        SettlementDto adjusted = settlementService.adjust(settlementId,
                new SettlementAdjustRequest(200, "补发跨区补贴"), "财务甲");
        assertEquals(200, adjusted.adjustAmount());
        assertEquals(500, adjusted.totalAmount());
        assertTrue(settlementItemDao.findBySettlement(settlementId).stream()
                .anyMatch(item -> EarningRuleEngine.ITEM_ADJUST.equals(item.itemType())
                        && item.calcDetail().contains("操作人 财务甲")));
    }

    @Test
    void voidReleasesItemsForRegeneration() {
        settleTask(LocalDateTime.of(2026, 8, 10, 10, 0), 0, true);
        long settlementId = settlementService
                .generate(SettlementService.PERIOD_DAILY, YESTERDAY, YESTERDAY, null).get(0);
        settlementService.voidSettlement(settlementId, "财务甲");
        assertEquals(SettlementService.STATUS_VOID, settlementService.detail(settlementId).status());
        assertEquals(0, settlementItemDao.findBySettlement(settlementId).size());
    }

    @Test
    void csvExportUsesDashboardStyleFilenameAndContent() {
        settleTask(LocalDateTime.of(2026, 8, 10, 21, 0), 2400, true);
        long settlementId = settlementService
                .generate(SettlementService.PERIOD_DAILY, YESTERDAY, YESTERDAY, null).get(0);
        Map<String, Object> export = settlementService.exportCsv(settlementId);
        String content = String.valueOf(export.get("content"));
        assertTrue(String.valueOf(export.get("filename")).startsWith("settlement-JS20260810"));
        assertTrue(content.startsWith("\uFEFF"));
        assertTrue(content.contains("费用类型"));
        assertTrue(content.contains("里程费"));
        assertTrue(content.contains("夜间补贴"));
        assertTrue(content.contains("合计"));
    }

    @Test
    void riderSummaryReadsEveryEarningComponent() {
        settleTask(LocalDateTime.of(2026, 8, 11, 21, 0), 2400, true);
        EarningSummaryDto summary = settlementService.summary(riderId, "TODAY");
        assertEquals("TODAY", summary.period());
        assertEquals(300, summary.baseAmount());
        assertEquals(70, summary.distanceAmount());
        assertEquals(100, summary.nightAmount());
        assertEquals(470, summary.totalAmount());
        assertEquals(1, summary.taskCount());
    }

    @Test
    void riderCannotReadAnotherRidersSettlement() {
        settleTask(LocalDateTime.of(2026, 8, 10, 10, 0), 0, true);
        long settlementId = settlementService
                .generate(SettlementService.PERIOD_DAILY, YESTERDAY, YESTERDAY, null).get(0);
        long otherRiderId = A6Fixtures.insertRider(jdbcTemplate, "郑十一", 100);
        assertThrows(DeliveryException.class, () -> settlementService.riderSettlement(otherRiderId, settlementId));
        assertEquals(settlementId, settlementService.riderSettlement(riderId, settlementId).id());
    }

    private void settleTask(LocalDateTime deliveredAt, int distanceMeters, boolean onTime) {
        long taskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, deliveredAt, onTime).withDistance(distanceMeters));
        earningRuleEngine.settleTask(taskId);
    }
}
