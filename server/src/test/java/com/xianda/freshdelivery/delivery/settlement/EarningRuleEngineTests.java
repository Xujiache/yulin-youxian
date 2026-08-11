package com.xianda.freshdelivery.delivery.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.domain.DeliverySettlementItem;
import com.xianda.freshdelivery.delivery.integration.DeliveryWeatherConditionPort;
import com.xianda.freshdelivery.delivery.integration.NoopWeatherService;
import com.xianda.freshdelivery.delivery.integration.WeatherService;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.task.DeliveryConfigPort;
import com.xianda.freshdelivery.delivery.task.TaskUnitOfWork;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EarningRuleEngineTests {
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 8, 11, 12, 0, 0);

    private JdbcTemplate jdbcTemplate;
    private DeliveryConfigService configService;
    private SettlementTaskQueryDao taskQueryDao;
    private SettlementItemDao settlementItemDao;
    private MutableClock clock;
    private long riderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate = A6Fixtures.database("a6_earning_engine");
        A6Fixtures.setConfig(jdbcTemplate, DeliveryConfigPort.FAMILY_MODE, "false");
        configService = new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate));
        taskQueryDao = new SettlementTaskQueryDao(jdbcTemplate);
        settlementItemDao = new SettlementItemDao(jdbcTemplate);
        clock = new MutableClock(NOON);
        riderId = A6Fixtures.insertRider(jdbcTemplate, "张三", 100);
    }

    @Test
    void baseFeeIsAlwaysPresent() {
        EarningRuleEngine.EarningBreakdown breakdown = engine(false).calculate(context(NOON, 0, "0", null, null));
        assertEquals(1, breakdown.lines().size());
        assertEquals(300, breakdown.totalAmount());
        assertTrue(breakdown.lines().get(0).calcDetail().contains("基础配送费：3.00 元"));
    }

    @Test
    void distanceFeeMatchesRuleDocumentExample() {
        EarningRuleEngine.EarningBreakdown breakdown = engine(false).calculate(context(NOON, 2400, "0", null, null));
        assertEquals(70, breakdown.amountOf(EarningRuleEngine.ITEM_DISTANCE));
        String detail = breakdown.lines().get(1).calcDetail();
        assertTrue(detail.contains("实际 2.4 公里"), detail);
        assertTrue(detail.contains("免计 1.0 公里"), detail);
        assertTrue(detail.contains("0.70 元"), detail);
    }

    @Test
    void distanceBoundaryNeverGoesNegative() {
        EarningRuleEngine engine = engine(false);
        EarningRuleEngine.EarningBreakdown under = engine.calculate(context(NOON, 999, "0", null, null));
        assertFalse(under.hasItem(EarningRuleEngine.ITEM_DISTANCE));
        assertEquals(0, under.amountOf(EarningRuleEngine.ITEM_DISTANCE));
        assertEquals(300, under.totalAmount());

        EarningRuleEngine.EarningBreakdown over = engine.calculate(context(NOON, 1001, "0", null, null));
        assertTrue(over.hasItem(EarningRuleEngine.ITEM_DISTANCE));
        assertEquals(1, over.amountOf(EarningRuleEngine.ITEM_DISTANCE));
    }

    @Test
    void weightBoundaryStartsAboveFiveKilograms() {
        EarningRuleEngine engine = engine(false);
        assertFalse(engine.calculate(context(NOON, 0, "5.0", null, null)).hasItem(EarningRuleEngine.ITEM_WEIGHT));
        EarningRuleEngine.EarningBreakdown over = engine.calculate(context(NOON, 0, "5.1", null, null));
        assertEquals(1, over.amountOf(EarningRuleEngine.ITEM_WEIGHT));
        assertTrue(over.lines().get(1).calcDetail().contains("免计 5.0 公斤"));
    }

    @Test
    void floorFeeOnlyWhenNoElevator() {
        EarningRuleEngine engine = engine(false);
        assertFalse(engine.calculate(context(NOON, 0, "0", 1, Boolean.FALSE)).hasItem(EarningRuleEngine.ITEM_FLOOR));
        assertEquals(100, engine.calculate(context(NOON, 0, "0", 6, Boolean.FALSE))
                .amountOf(EarningRuleEngine.ITEM_FLOOR));
        assertFalse(engine.calculate(context(NOON, 0, "0", 6, null)).hasItem(EarningRuleEngine.ITEM_FLOOR));
        assertFalse(engine.calculate(context(NOON, 0, "0", 6, Boolean.TRUE)).hasItem(EarningRuleEngine.ITEM_FLOOR));
    }

    @Test
    void nightSubsidyBoundaryIsTwentyOClock() {
        EarningRuleEngine engine = engine(false);
        LocalDateTime before = LocalDateTime.of(2026, 8, 11, 19, 59, 0);
        LocalDateTime after = LocalDateTime.of(2026, 8, 11, 20, 1, 0);
        assertFalse(engine.calculate(context(before, 0, "0", null, null)).hasItem(EarningRuleEngine.ITEM_NIGHT));
        assertEquals(100, engine.calculate(context(after, 0, "0", null, null)).amountOf(EarningRuleEngine.ITEM_NIGHT));
        LocalDateTime earlyMorning = LocalDateTime.of(2026, 8, 11, 5, 30, 0);
        assertEquals(100, engine.calculate(context(earlyMorning, 0, "0", null, null))
                .amountOf(EarningRuleEngine.ITEM_NIGHT));
    }

    @Test
    void badWeatherSubsidyComesFromTheWeatherPort() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate, A6Fixtures.TaskSpec.delivered(riderId, NOON, true));
        assertEquals(100, engine(true).preview(taskId).amountOf(EarningRuleEngine.ITEM_WEATHER));
        assertEquals(0, engine(false).preview(taskId).amountOf(EarningRuleEngine.ITEM_WEATHER));
    }

    @Test
    void holidaySubsidyAppliesOnStatutoryHolidays() {
        LocalDateTime nationalDay = LocalDateTime.of(2026, 10, 1, 12, 0);
        assertEquals(150, engine(false).calculate(context(nationalDay, 0, "0", null, null))
                .amountOf(EarningRuleEngine.ITEM_HOLIDAY));
        assertEquals(0, engine(false).calculate(context(NOON, 0, "0", null, null))
                .amountOf(EarningRuleEngine.ITEM_HOLIDAY));
    }

    @Test
    void allSevenItemsCanCoexist() {
        EarningRuleEngine.EarningBreakdown breakdown = engine(true).calculate(new EarningRuleEngine.EarningContext(
                3000, new BigDecimal("8.0"), 6, Boolean.FALSE,
                LocalDateTime.of(2026, 10, 1, 21, 0), true, true));
        assertEquals(7, breakdown.lines().size());
        assertEquals(300 + 100 + 30 + 100 + 100 + 100 + 150, breakdown.totalAmount());
    }

    @Test
    void overtimeTaskEarnsExactlyAsMuchAsOnTimeTask() {
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 8, 11, 21, 30);
        A6Fixtures.setElevator(jdbcTemplate, "GK-EQUAL", Boolean.FALSE);
        long onTimeTaskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, deliveredAt, true)
                        .withDistance(2400).withWeight("8.0").withBuilding("GK-EQUAL", 6));
        long overtimeTaskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, deliveredAt, false)
                        .withDistance(2400).withWeight("8.0").withBuilding("GK-EQUAL", 6));

        EarningRuleEngine engine = engine(false);
        engine.settleTask(onTimeTaskId);
        engine.settleTask(overtimeTaskId);

        List<DeliverySettlementItem> onTimeItems = settlementItemDao.findByTask(onTimeTaskId);
        List<DeliverySettlementItem> overtimeItems = settlementItemDao.findByTask(overtimeTaskId);
        assertEquals(onTimeItems.size(), overtimeItems.size());
        assertEquals(sum(onTimeItems), sum(overtimeItems));
        assertEquals(300 + 70 + 30 + 100 + 100, sum(overtimeItems));
        assertTrue(overtimeItems.stream().allMatch(item -> item.amount() >= 0),
                "结算明细不允许出现负数金额（超时不得扣款）");
    }

    @Test
    void settleTaskIsIdempotent() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, NOON, true).withDistance(2400));
        EarningRuleEngine engine = engine(false);
        engine.settleTask(taskId);
        engine.settleTask(taskId);
        assertEquals(2, settlementItemDao.findByTask(taskId).size());
    }

    @Test
    void retryRepairsPreviouslyPartialEarningBreakdown() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, NOON, true).withDistance(2400));
        settlementItemDao.insert(new DeliverySettlementItem(
                null, null, riderId, taskId, "PARTIAL", EarningRuleEngine.ITEM_BASE,
                300, "模拟上次只写入基础费后崩溃", NOON, NOON), NOON);

        engine(false).settleTask(taskId);

        List<DeliverySettlementItem> items = settlementItemDao.findByTask(taskId);
        assertEquals(2, items.size());
        assertEquals(370, sum(items));
        assertTrue(taskQueryDao.earningCompleted(taskId));
    }

    @Test
    void partialInsertFailureRollsBackAndCanBeRetriedCompletely() {
        long taskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, NOON, true).withDistance(2400));
        SettlementItemDao failAfterFirstInsert = new SettlementItemDao(jdbcTemplate) {
            private boolean failed;

            @Override
            public long insert(DeliverySettlementItem item, LocalDateTime now) {
                long id = super.insert(item, now);
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("模拟收入明细写到一半失败");
                }
                return id;
            }
        };
        EarningRuleEngine failingEngine = new EarningRuleEngine(
                taskQueryDao, failAfterFirstInsert, configService, new HolidayCalendar(),
                A6Fixtures.provider(new DeliveryWeatherConditionPort(new NoopWeatherService(), null)), clock,
                TaskUnitOfWork.transactional(new TransactionTemplate(
                        new DataSourceTransactionManager(jdbcTemplate.getDataSource()))));

        assertThrows(IllegalStateException.class, () -> failingEngine.settleTask(taskId));
        assertTrue(settlementItemDao.findByTask(taskId).isEmpty());
        assertFalse(taskQueryDao.earningCompleted(taskId));

        engine(false).settleTask(taskId);
        assertEquals(370, sum(settlementItemDao.findByTask(taskId)));
        assertTrue(taskQueryDao.earningCompleted(taskId));
    }

    @Test
    void familyModeCreatesNoEarningSideEffects() {
        A6Fixtures.setConfig(jdbcTemplate, DeliveryConfigPort.FAMILY_MODE, "true");
        configService.invalidate();
        long taskId = A6Fixtures.insertTask(jdbcTemplate,
                A6Fixtures.TaskSpec.delivered(riderId, NOON, true).withDistance(2400));

        engine(false).settleTask(taskId);

        assertTrue(settlementItemDao.findByTask(taskId).isEmpty());
        assertFalse(taskQueryDao.earningCompleted(taskId));
    }

    private int sum(List<DeliverySettlementItem> items) {
        return items.stream().mapToInt(DeliverySettlementItem::amount).sum();
    }

    private EarningRuleEngine.EarningContext context(LocalDateTime occurredAt, int distanceMeters, String weightKg,
                                                     Integer floorNo, Boolean hasElevator) {
        return new EarningRuleEngine.EarningContext(distanceMeters, new BigDecimal(weightKg), floorNo, hasElevator,
                occurredAt, false, new HolidayCalendar().isStatutoryHoliday(occurredAt.toLocalDate()));
    }

    private EarningRuleEngine engine(boolean badWeather) {
        WeatherService weatherService = badWeather ? new StormyWeatherService() : new NoopWeatherService();
        DeliveryWeatherConditionPort port = new DeliveryWeatherConditionPort(weatherService, null);
        return new EarningRuleEngine(taskQueryDao, settlementItemDao, configService, new HolidayCalendar(),
                A6Fixtures.provider(port), clock);
    }

    private static final class StormyWeatherService implements WeatherService {
        @Override
        public String provider() {
            return "TEST_STORM";
        }

        @Override
        public WeatherSnapshot snapshot(LocalDateTime at) {
            return new WeatherSnapshot("暴雨", true, 1.4d, "TEST_STORM");
        }
    }
}
