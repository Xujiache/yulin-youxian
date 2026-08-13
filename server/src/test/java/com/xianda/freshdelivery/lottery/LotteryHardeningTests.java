package com.xianda.freshdelivery.lottery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.CartDto;
import com.xianda.freshdelivery.dto.CreateAddressRequest;
import com.xianda.freshdelivery.dto.CreateOrderRequest;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundRequest;
import com.xianda.freshdelivery.lottery.LotteryDao.DrawGuardKey;
import com.xianda.freshdelivery.lottery.LotteryModels.AdminDraw;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderPromotionProjection;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderState;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicCampaign;
import com.xianda.freshdelivery.lottery.LotteryModels.ReasonCode;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import com.xianda.freshdelivery.persistence.FileStateStore;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPayClient;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 覆盖随机减免的一批一致性缺陷：待支付订单不能履约、投影修复不改实付、快照可回滚、
 * 关单先于轮换单号、赠品拿不回时降级、退款回补口径、对账收敛与终态、契约字段。
 */
class LotteryHardeningTests {
    private static final long USER_ID = 92001L;
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    private Path statePath;
    private StorefrontService storefront;
    private JdbcTemplate jdbcTemplate;
    private LotteryDao dao;
    private MutableClock clock;
    private RecordingPayClient payClient;
    private LotteryService service;
    private Long addressId;

    @BeforeEach
    void setUp() {
        CurrentUserContext.setUserId(USER_ID);
        statePath = tempDir.resolve("storefront.json");
        LifecycleProvider lifecycleProvider = new LifecycleProvider();
        storefront = new StorefrontService(
                statePath.toString(),
                true,
                new FileStateStore(),
                null,
                lifecycleProvider
        );
        jdbcTemplate = DeliveryTestDatabase.create("lottery_hardening_" + System.nanoTime());
        dao = new LotteryDao(jdbcTemplate);
        clock = new MutableClock(Instant.parse("2026-08-12T01:00:00Z"), STORE_ZONE);
        payClient = new RecordingPayClient();
        service = new LotteryService(
                dao,
                storefront,
                payClient,
                clock,
                new FirstCandidateRandom(),
                Duration.ofMinutes(10)
        );
        lifecycleProvider.delegate = service;
        addressId = storefront.createAddress(new CreateAddressRequest(
                "抽奖加固用户",
                "13800000001",
                "2号楼 202室",
                "测试小区",
                31.2304,
                121.4737,
                true
        )).id();
    }

    @AfterEach
    void clearUser() {
        CurrentUserContext.clear();
    }

    @Test
    void pendingOrderGiftCannotBeFulfilledSoBothStocksStayRefundable() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10))));
        BigDecimal productStockBefore = storefront.product(104L).stockQty();
        OrderDetailDto order = createOrder();
        DrawResult gift = triggerAndDraw(order);

        BusinessException blocked = assertThrows(
                BusinessException.class,
                () -> service.fulfill(gift.drawId(), "运营提前点了确认")
        );
        assertEquals(409, blocked.code());
        assertTrue(blocked.getMessage().contains("尚未支付"), blocked.getMessage());

        storefront.cancelOrder(order.id(), false);

        assertEquals(productStockBefore, storefront.product(104L).stockQty(), "真实商品库存必须回补");
        assertEquals(1, marketingStockRemaining(), "营销库存必须回补");
    }

    @Test
    void paidOrderGiftFulfillmentSucceedsAndReachesTerminalState() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10))));
        OrderDetailDto order = createOrder();
        DrawResult gift = triggerAndDraw(order);
        payOrder(order.id());

        AdminDraw fulfilled = service.fulfill(gift.drawId(), "已随单送达");

        assertEquals("FULFILLED", fulfilled.status());
        assertEquals("SETTLED", fulfilled.relationStatus());
        assertEquals("已随单送达", fulfilled.fulfillmentRemark());
        assertNotNull(fulfilled.paidAt());
    }

    @Test
    void repairingProjectionNeverRewritesPaidAmountOfSettledOrder() {
        OrderDetailDto order = createOrder();
        int payable = order.payableAmount();
        payOrder(order.id());

        storefront.repairLotteryProjection(order.id(), new OrderPromotionProjection(
                9001L,
                8001L,
                0,
                "DISCOUNT",
                "随机减 100 分",
                100,
                null,
                null,
                null,
                payable - 100,
                List.of(),
                "APPLIED"
        ));

        OrderDetailDto repaired = storefront.adminOrder(order.id());
        assertEquals(payable, repaired.paidAmount(), "实付金额不能被投影推导覆盖");
        assertEquals(payable, repaired.payableAmount(), "非待支付订单的应付额不再回改");
        assertNotNull(repaired.lotteryResult(), "投影本身仍要补回来");

        RefundDto refund = storefront.createAdminRefund(new AdminRefundCreateRequest(
                USER_ID,
                order.id(),
                payable,
                "用户按原价付过款"
        ));
        assertEquals(payable, refund.refundAmount(), "可退金额不能跟着投影缩水");
    }

    @Test
    void snapshotOmitsPromotionsWhenUnusedAndToleratesUnknownFields() throws Exception {
        OrderDetailDto order = createOrder();
        String json = Files.readString(statePath);
        assertFalse(json.contains("orderPromotions"), "没用过抽奖时不写该字段，旧版本才能回滚");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode snapshot = (ObjectNode) mapper.readTree(json);
        snapshot.put("fieldAddedByANewerVersion", 1);
        Files.writeString(statePath, mapper.writeValueAsString(snapshot));

        StorefrontService reloaded = new StorefrontService(statePath.toString(), false);
        CurrentUserContext.setUserId(USER_ID);

        assertEquals(order.payableAmount(), reloaded.order(order.id()).payableAmount());
    }

    @Test
    void drawTakesRowGuardsForUserAndBudgetInsteadOfLockingTheCampaign() {
        service.saveCampaign(campaign(2, 100000, List.of(discountPrize(100, 10))));
        triggerAndDraw(createOrder());

        assertEquals(1, guardRows(USER_ID), "用户维度护栏行");
        assertEquals(1, guardRows(0L), "当日预算护栏行");
    }

    @Test
    void unlimitedBudgetDrawDoesNotTakeTheBudgetGuard() {
        service.saveCampaign(campaign(2, null, List.of(discountPrize(100, 10))));
        triggerAndDraw(createOrder());

        assertEquals(1, guardRows(USER_ID));
        assertEquals(0, guardRows(0L), "没有预算上限时不需要跟所有人抢同一行");
    }

    /**
     * 每日预算和每日次数能不能成为硬约束，取决于统计之前真的拿到了排他行锁。
     */
    @Test
    void guardRowBlocksTheSecondTransactionUntilTheFirstCommits() throws Exception {
        jdbcTemplate.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );
        DrawGuardKey key = new DrawGuardKey(1L, LocalDate.of(2026, 8, 12), LotteryDao.BUDGET_GUARD_USER);
        dao.lockDrawGuard(key);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean contenderAcquired = new AtomicBoolean();

        Thread holder = new Thread(() -> transactions.execute(status -> {
            dao.lockDrawGuard(key);
            locked.countDown();
            awaitQuietly(release);
            return null;
        }));
        holder.start();
        assertTrue(locked.await(5, TimeUnit.SECONDS));

        Thread contender = new Thread(() -> transactions.execute(status -> {
            dao.lockDrawGuard(key);
            contenderAcquired.set(true);
            return null;
        }));
        contender.start();
        Thread.sleep(300);
        assertFalse(contenderAcquired.get(), "护栏行被占用时第二个事务必须排队");

        release.countDown();
        contender.join(10_000);
        holder.join(10_000);
        assertTrue(contenderAcquired.get(), "前一个事务提交后应当拿到锁并看到最新统计");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void discountDrawClosesThePrepayBeforeRotatingTheTradeNo() {
        service.saveCampaign(campaign(3, null, List.of(discountPrize(100, 10))));
        OrderDetailDto order = createOrder();
        String originalPaymentNo = order.paymentOrderNo();

        triggerAndDraw(order);

        assertEquals(List.of(originalPaymentNo), payClient.closedPaymentNos, "关掉的必须是轮换前的旧单号");
        assertNotEquals(originalPaymentNo, storefront.order(order.id()).paymentOrderNo());
    }

    @Test
    void restartFallsBackToOriginalPriceWhenGiftCanNoLongerBeReserved() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10), nonePrize(20))));
        OrderDetailDto order = createOrder();
        DrawResult gift = triggerAndDraw(order);
        assertEquals("GOODS", gift.prizeType());

        LocalDateTime expiredAt = LocalDateTime.parse(order.createdAt().replace(" ", "T")).plusHours(7);
        storefront.closeExpiredOrders(expiredAt);
        disableGiftPrize();

        OrderDetailDto restarted = storefront.restartOrder(order.id(), 3L, expiredAt);

        assertEquals("待支付", restarted.status());
        assertTrue(restarted.gifts().isEmpty(), "拿不回来的赠品不能继续挂在订单上");
        assertEquals(order.payableAmount(), restarted.payableAmount());
        AdminDraw draw = adminDraws().get(0);
        assertEquals("VOIDED", draw.relationStatus());
        assertEquals("RESTART_MARKETING_STOCK_UNAVAILABLE", draw.voidReason());
    }

    @Test
    void fullRefundKeepsDispatchedGiftButRestoresUndispatchedOne() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 2, 10))));
        BigDecimal productStockBefore = storefront.product(104L).stockQty();

        OrderDetailDto dispatched = createOrder();
        triggerAndDraw(dispatched);
        payOrder(dispatched.id());
        storefront.deliverOrder(dispatched.id());
        approveFullRefund(dispatched.id());

        assertEquals(
                productStockBefore.subtract(BigDecimal.ONE),
                storefront.product(104L).stockQty(),
                "已装车的赠品不回补真实库存"
        );
        assertEquals(1, marketingStockRemaining(), "营销库存同样按损失处理");

        OrderDetailDto pending = createOrder();
        triggerAndDraw(pending);
        payOrder(pending.id());
        approveFullRefund(pending.id());

        assertEquals(
                productStockBefore.subtract(BigDecimal.ONE),
                storefront.product(104L).stockQty(),
                "备货中退款要把没发出的赠品还回来"
        );
        assertEquals(1, marketingStockRemaining());
    }

    @Test
    void reconcileClosesPrepayBeforeRestoringPriceAndConverges() {
        service.saveCampaign(campaign(3, null, List.of(discountPrize(100, 10))));
        OrderDetailDto order = createOrder();
        DrawResult drawn = triggerAndDraw(order);
        String discountedPaymentNo = storefront.order(order.id()).paymentOrderNo();
        jdbcTemplate.update("DELETE FROM marketing_lottery_draw WHERE id = ?", drawn.drawId());
        payClient.closedPaymentNos.clear();

        service.reconcile();

        assertEquals(List.of(discountedPaymentNo), payClient.closedPaymentNos, "恢复原价前必须先关单");
        OrderDetailDto restored = storefront.order(order.id());
        assertEquals(order.payableAmount(), restored.payableAmount());
        assertNotEquals(discountedPaymentNo, restored.paymentOrderNo());
        assertNull(restored.lotteryResult());

        service.reconcile();
        assertEquals(1, payClient.closedPaymentNos.size(), "投影已清掉，不该反复修同一笔");
    }

    @Test
    void reconcileClearsProjectionOnPaidOrderWithoutTouchingAmounts() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10))));
        BigDecimal productStockBefore = storefront.product(104L).stockQty();
        OrderDetailDto order = createOrder();
        DrawResult gift = triggerAndDraw(order);
        payOrder(order.id());
        int paidAmount = storefront.adminOrder(order.id()).paidAmount();
        jdbcTemplate.update("DELETE FROM marketing_lottery_draw WHERE id = ?", gift.drawId());

        service.reconcile();

        OrderDetailDto repaired = storefront.adminOrder(order.id());
        assertNull(repaired.lotteryResult(), "投影必须被清掉，否则对账每轮都会重来一次");
        assertEquals(paidAmount, repaired.paidAmount());
        assertEquals(productStockBefore, storefront.product(104L).stockQty(), "赠品真实库存回补一次");

        service.reconcile();
        assertEquals(productStockBefore, storefront.product(104L).stockQty(), "不能重复回补");
    }

    @Test
    void completedOrderDrawLeavesTheReconcileScope() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10))));
        OrderDetailDto order = createOrder();
        triggerAndDraw(order);
        payOrder(order.id());
        storefront.deliverOrder(order.id());
        storefront.completeOrder(order.id());

        assertEquals("SETTLED", adminDraws().get(0).relationStatus());
        assertTrue(
                dao.findDrawsForReconcile("APPLIED", LocalDateTime.of(2026, 8, 1, 0, 0), 100).isEmpty(),
                "终态流水不该再被对账扫到"
        );
    }

    @Test
    void adminDrawsArePagedAndCarryAuditFields() {
        service.saveCampaign(campaign(3, null, List.of(nonePrize(10))));
        OrderDetailDto first = createOrder();
        OrderDetailDto second = createOrder();
        triggerAndDraw(first);
        triggerAndDraw(second);

        PageResult<AdminDraw> page = service.draws(null, null, null, null, null, 1, 1);

        assertEquals(1, page.items().size());
        assertEquals(2, page.total());
        assertEquals(1, page.page());
        assertEquals(1, page.pageSize());
        AdminDraw latest = page.items().get(0);
        assertEquals(second.id(), latest.orderId());
        assertNotNull(latest.shareTriggeredAt(), "分享触发时间来自 challenge");
        assertEquals("待支付", latest.orderStatus());
        assertNull(latest.paidAt());

        payOrder(second.id());
        AdminDraw afterPayment = service.draws(null, null, null, null, null, 1, 1).items().get(0);
        assertNotNull(afterPayment.paidAt(), "支付完成时间要补齐");
        assertEquals("备货中", afterPayment.orderStatus());
    }

    @Test
    void drawnAtTracksWhenThePrizeTookEffectNotWhenTheRowWasCreated() {
        service.saveCampaign(campaign(3, null, List.of(nonePrize(10))));
        OrderDetailDto order = createOrder();
        triggerAndDraw(order);
        // 快照已经写好、流水状态还停在 RESERVED，正是对账要补的那一步。
        jdbcTemplate.update(
                "UPDATE marketing_lottery_draw SET status = 'RESERVED', applied_at = NULL WHERE order_id = ?",
                order.id()
        );
        clock.advance(Duration.ofMinutes(5));

        service.reconcile();

        AdminDraw draw = adminDraws().get(0);
        assertEquals("APPLIED", draw.relationStatus());
        assertNotEquals(draw.createdAt(), draw.drawnAt(), "drawnAt 是奖项生效时间，与流水创建时间语义不同");
    }

    @Test
    void staleChallengesAreCleanedUp() {
        service.saveCampaign(campaign(3, null, List.of(nonePrize(10))));
        service.challenge(createOrder().id());
        assertEquals(1, challengeRows());

        clock.advance(Duration.ofDays(8));
        service.cleanupChallenges();

        assertEquals(0, challengeRows(), "已过期的挑战不能永远留在表里");
    }

    @Test
    void wheelOnlyShowsPrizesThatCanStillBeWon() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10), nonePrize(20))));
        OrderDetailDto first = createOrder();
        OrderDetailDto second = createOrder();
        assertEquals(2, service.orderState(second.id()).prizes().size());

        assertEquals("GOODS", triggerAndDraw(first).prizeType());

        OrderState state = service.orderState(second.id());
        assertEquals(1, state.prizes().size(), "库存抽光的赠品不能继续出现在转盘上");
        assertEquals("NONE", state.prizes().get(0).type());
    }

    @Test
    void prizeIndexPointsIntoTheListSentToTheClient() {
        Prize disabledDiscount = new Prize(
                null, "DISCOUNT", "已停用减免", 100,
                null, null, null, 0, 0, null, false, 10
        );
        service.saveCampaign(campaign(2, null, List.of(disabledDiscount, nonePrize(20))));
        OrderDetailDto order = createOrder();
        OrderState state = service.orderState(order.id());
        assertEquals(1, state.prizes().size());

        DrawResult result = triggerAndDraw(order);

        assertEquals(0, result.prizeIndex(), "下标必须落在下发列表里，而不是未过滤的原始列表");
        assertEquals(state.prizes().get(0).id(), result.prizeId());
    }

    @Test
    void orderStateCarriesStableReasonCodes() {
        service.saveCampaign(campaign(1, null, List.of(nonePrize(10))));
        OrderDetailDto first = createOrder();
        OrderDetailDto second = createOrder();
        assertEquals(ReasonCode.ELIGIBLE, service.orderState(first.id()).reasonCode());

        triggerAndDraw(first);
        OrderState drawn = service.orderState(first.id());
        assertEquals(ReasonCode.ALREADY_DRAWN, drawn.reasonCode());
        assertEquals("该订单已抽奖", drawn.reason());

        assertEquals(
                ReasonCode.DAILY_LIMIT_REACHED,
                service.orderState(second.id()).reasonCode()
        );

        storefront.cancelOrder(second.id(), false);
        assertEquals(ReasonCode.ORDER_NOT_PENDING, service.orderState(second.id()).reasonCode());

        OrderDetailDto third = createOrder();
        service.saveCampaign(disabled(service.adminCampaign()));
        assertEquals(ReasonCode.CAMPAIGN_DISABLED, service.orderState(third.id()).reasonCode());
    }

    @Test
    void lineItemRefundDeductsItsShareOfTheDiscount() {
        service.saveCampaign(campaign(2, null, List.of(discountPrize(100, 10))));
        OrderDetailDto order = createMultiItemOrder();
        triggerAndDraw(order);
        payOrder(order.id());

        OrderDetailDto paid = storefront.adminOrder(order.id());
        long lineId = paid.items().get(0).id();
        int lineAmount = paid.items().get(0).amount();
        int allocated = Math.round(100f * lineAmount / paid.productAmount());

        assertThrows(BusinessException.class, () -> storefront.createRefund(new RefundRequest(
                order.id(),
                List.of(lineId),
                lineAmount,
                "整行按原价退",
                List.of()
        )), "按原价退会把减免全甩给后退的那行");

        RefundDto refund = storefront.createRefund(new RefundRequest(
                order.id(),
                List.of(lineId),
                lineAmount - allocated,
                "按减免比例分摊",
                List.of()
        ));
        assertEquals(lineAmount - allocated, refund.refundAmount());
    }

    @Test
    void drawnOrderKeepsTheWinningPrizeAndRealignsItsIndex() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10), nonePrize(20))));
        OrderDetailDto order = createOrder();
        DrawResult result = triggerAndDraw(order);
        assertEquals("GOODS", result.prizeType());
        assertEquals(0, marketingStockRemaining());

        OrderState afterDraw = service.orderState(order.id());
        assertEquals(2, afterDraw.prizes().size(), "库存已归零，中奖奖项仍要留在下发列表里");
        assertEquals(
                result.prizeId(),
                afterDraw.prizes().get(afterDraw.result().prizeIndex()).id(),
                "prizeIndex 必须指向下发列表里的中奖奖项"
        );

        // 运营调整排序后下发列表顺序变了，下标要按本次下发的列表重算而不是沿用流水里的旧值。
        jdbcTemplate.update("UPDATE marketing_lottery_prize SET sort_order = 30 WHERE type = 'GOODS'");
        OrderState reordered = service.orderState(order.id());
        assertEquals(1, reordered.result().prizeIndex());
        assertEquals(result.prizeId(), reordered.prizes().get(1).id());
    }

    @Test
    void drawSearchPushesTheTimeWindowIntoSqlAndPaging() {
        service.saveCampaign(campaign(5, null, List.of(nonePrize(10))));
        OrderDetailDto old = createOrder();
        triggerAndDraw(old);
        jdbcTemplate.update(
                "UPDATE marketing_lottery_draw SET created_at = '2026-08-01 10:00:00' WHERE order_id = ?",
                old.id()
        );
        OrderDetailDto recent = createOrder();
        triggerAndDraw(recent);

        PageResult<AdminDraw> window = service.draws(
                null, null, null, "2026-08-10T00:00:00", "2026-08-12T23:59:59", 1, 20);
        assertEquals(1, window.total(), "时间窗要参与计数，否则分页器显示的总数是全量");
        assertEquals(recent.id(), window.items().get(0).orderId());
        assertTrue(
                service.draws(null, null, null, "2026-08-10T00:00:00", "2026-08-12T23:59:59", 2, 1)
                        .items().isEmpty(),
                "窗口外的记录不能在翻页时冒出来"
        );
        assertEquals(2, service.draws(null, null, null, null, null, 1, 20).total());
        assertEquals(
                1,
                service.draws(null, null, null, "2026-08-01", "2026-08-01", 1, 20).total(),
                "只给日期时按当天 00:00:00 到 23:59:59 处理"
        );
        assertEquals(
                2,
                service.draws(null, null, null, "不是时间", null, 1, 20).total(),
                "无法解析的时间值当作没有筛选，不能把列表清空"
        );
    }

    @Test
    void everyAcceptedStatusFilterAgreesWithTheStatusesTheApiReturns() {
        service.saveCampaign(campaign(9, null, List.of(goodsPrize(104L, 2, 10), nonePrize(20))));
        OrderDetailDto applied = createOrder();
        triggerAndDraw(applied);
        payOrder(applied.id());

        OrderDetailDto settled = createOrder();
        DrawResult settledDraw = triggerAndDraw(settled);
        payOrder(settled.id());
        service.fulfill(settledDraw.drawId(), "门店自提已交付");

        // 赠品库存抽完之后才会轮到 NONE，用来覆盖 NOT_REQUIRED。
        OrderDetailDto notRequired = createOrder();
        triggerAndDraw(notRequired);

        OrderDetailDto voided = createOrder();
        triggerAndDraw(voided);
        storefront.cancelOrder(voided.id(), false);

        OrderDetailDto reserved = createOrder();
        triggerAndDraw(reserved);
        jdbcTemplate.update(
                "UPDATE marketing_lottery_draw SET status = 'RESERVED' WHERE order_id = ?",
                reserved.id()
        );

        List<AdminDraw> all = adminDraws();
        assertEquals(5, all.size());
        for (String status : List.of(
                "PENDING", "FULFILLED", "RELEASED", "NOT_REQUIRED", "VOIDED",
                "RESERVED", "APPLIED", "SETTLED"
        )) {
            List<Long> expected = all.stream()
                    .filter(draw -> status.equals(draw.status()) || status.equals(draw.relationStatus()))
                    .map(AdminDraw::id)
                    .sorted()
                    .toList();
            List<Long> actual = service.draws(null, null, status, null, null, 1, 50).items().stream()
                    .map(AdminDraw::id)
                    .sorted()
                    .toList();
            assertEquals(expected, actual, "状态筛选 " + status + " 与列表里的状态口径不一致");
        }
        List<String> fulfillmentStatuses = all.stream().map(AdminDraw::status).distinct().sorted().toList();
        assertEquals(List.of("FULFILLED", "NOT_REQUIRED", "PENDING", "VOIDED"), fulfillmentStatuses);
        List<String> relationStatuses = all.stream().map(AdminDraw::relationStatus).distinct().sorted().toList();
        assertEquals(List.of("APPLIED", "RESERVED", "SETTLED", "VOIDED"), relationStatuses);
    }

    @Test
    void unlimitedDailyBudgetSurvivesTheSaveReadSaveRoundTrip() {
        service.saveCampaign(campaign(2, 10_000, List.of(discountPrize(100, 10))));
        assertEquals(10_000, service.adminCampaign().dailyBudgetAmount());

        service.saveCampaign(campaign(2, null, List.of(discountPrize(100, 10))));
        assertNull(service.adminCampaign().dailyBudgetAmount(), "不限预算必须存成 NULL 而不是 0");
        assertNull(jdbcTemplate.queryForObject(
                "SELECT daily_budget_amount FROM marketing_lottery_campaign", Integer.class));

        // 回读到的活动原样再存一次，仍然是不限预算。
        service.saveCampaign(service.adminCampaign());
        assertNull(service.adminCampaign().dailyBudgetAmount());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT daily_budget_amount FROM marketing_lottery_campaign", Integer.class));
    }

    @Test
    void drawsEndpointAcceptsPageSizeAliasFromTheAdminConsole() {
        service.saveCampaign(campaign(5, null, List.of(nonePrize(10))));
        triggerAndDraw(createOrder());
        triggerAndDraw(createOrder());
        AdminLotteryController controller = new AdminLotteryController(service);

        PageResult<AdminDraw> aliased = controller
                .draws(null, null, null, null, null, 1, null, 1)
                .data();

        assertEquals(1, aliased.pageSize(), "后台为兼容旧命名只发 pageSize 时也要能分页");
        assertEquals(2, aliased.total());
    }

    @Test
    void publicCampaignIsCachedAndInvalidatedOnSave() {
        service.saveCampaign(campaign(2, null, List.of(nonePrize(10))));

        PublicCampaign cached = service.publicCampaign();
        assertSame(cached, service.publicCampaign());

        clock.advance(Duration.ofSeconds(31));
        assertNotSame(cached, service.publicCampaign(), "TTL 到期后要重新读库");

        PublicCampaign refreshed = service.publicCampaign();
        service.saveCampaign(campaign(2, null, List.of(nonePrize(10))));
        assertNotSame(refreshed, service.publicCampaign(), "改配置后缓存必须失效");
    }

    private void payOrder(long orderId) {
        OrderDetailDto order = storefront.adminOrder(orderId);
        storefront.confirmPayment(new PaymentNotifyRequest(
                order.paymentOrderNo(),
                "TX-" + orderId,
                "SUCCESS",
                "wx-test",
                "mch-test",
                order.payableAmount()
        ));
    }

    private void approveFullRefund(long orderId) {
        OrderDetailDto order = storefront.adminOrder(orderId);
        RefundDto refund = storefront.createAdminRefund(new AdminRefundCreateRequest(
                USER_ID,
                orderId,
                order.paidAmount(),
                "全额退款"
        ));
        storefront.approveRefund(refund.id());
    }

    private void disableGiftPrize() {
        Campaign current = service.adminCampaign();
        Tier tier = current.tiers().get(0);
        List<Prize> prizes = tier.prizes().stream()
                .map(prize -> "GOODS".equals(prize.type())
                        ? new Prize(
                                prize.id(), prize.type(), prize.name(), prize.discountAmount(),
                                prize.productId(), prize.skuId(), prize.imageUrl(), 0,
                                prize.stockTotal(), prize.stockRemaining(), false, prize.sortOrder())
                        : prize)
                .toList();
        service.saveCampaign(new Campaign(
                current.id(),
                current.enabled(),
                current.name(),
                current.startAt(),
                current.endAt(),
                current.dailyUserLimit(),
                current.dailyBudgetAmount(),
                current.shareTitle(),
                current.shareDescription(),
                current.shareImageUrl(),
                List.of(new Tier(
                        tier.id(),
                        tier.name(),
                        tier.minProductAmount(),
                        tier.maxProductAmount(),
                        tier.enabled(),
                        tier.sortOrder(),
                        prizes
                ))
        ));
    }

    private Campaign disabled(Campaign current) {
        return new Campaign(
                current.id(),
                false,
                current.name(),
                current.startAt(),
                current.endAt(),
                current.dailyUserLimit(),
                current.dailyBudgetAmount(),
                current.shareTitle(),
                current.shareDescription(),
                current.shareImageUrl(),
                current.tiers()
        );
    }

    private List<AdminDraw> adminDraws() {
        return service.draws(null, null, null, null, null, 1, 50).items();
    }

    private int marketingStockRemaining() {
        return service.adminCampaign().tiers().get(0).prizes().stream()
                .filter(prize -> "GOODS".equals(prize.type()))
                .findFirst()
                .orElseThrow()
                .stockRemaining();
    }

    private int guardRows(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketing_lottery_draw_guard WHERE user_id = ?",
                Integer.class,
                userId
        );
        return count == null ? 0 : count;
    }

    private int challengeRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM marketing_lottery_challenge",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private DrawResult triggerAndDraw(OrderDetailDto order) {
        OrderState challenge = service.challenge(order.id());
        service.shareTriggered(order.id(), challenge.challengeToken());
        return service.draw(order.id(), challenge.challengeToken());
    }

    private OrderDetailDto createOrder() {
        storefront.addCartItem(106L, BigDecimal.ONE);
        CartDto cart = storefront.cart();
        return storefront.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "",
                cart.items().stream().map(item -> item.id()).toList()
        ));
    }

    private OrderDetailDto createMultiItemOrder() {
        storefront.addCartItem(106L, BigDecimal.ONE);
        storefront.addCartItem(104L, BigDecimal.ONE);
        CartDto cart = storefront.cart();
        return storefront.createOrder(new CreateOrderRequest(
                addressId,
                1L,
                "",
                cart.items().stream().map(item -> item.id()).toList()
        ));
    }

    private Campaign campaign(Integer dailyLimit, Integer dailyBudget, List<Prize> prizes) {
        return new Campaign(
                null,
                true,
                "随机减免加固测试",
                "2026-08-01T00:00:00",
                "2026-09-01T00:00:00",
                dailyLimit,
                dailyBudget,
                "分享后抽奖",
                "仅记录朋友圈菜单触发",
                "/lottery.png",
                List.of(new Tier(null, "满 10 元", 1000, null, true, 10, prizes))
        );
    }

    private Prize discountPrize(int amount, int sortOrder) {
        return new Prize(
                null, "DISCOUNT", "随机减 " + amount + " 分", amount,
                null, null, null, 1, 0, null, true, sortOrder
        );
    }

    private Prize goodsPrize(long productId, int stock, int sortOrder) {
        return new Prize(
                null, "GOODS", "随机赠品", null,
                productId, null, null, 1, stock, null, true, sortOrder
        );
    }

    private Prize nonePrize(int sortOrder) {
        return new Prize(
                null, "NONE", "谢谢参与", null,
                null, null, null, 1, 0, null, true, sortOrder
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FirstCandidateRandom extends SecureRandom {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public int nextInt(int bound) {
            return 0;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            int value = sequence.incrementAndGet();
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (value + index);
            }
        }
    }

    private static final class RecordingPayClient extends WechatPayClient {
        private final List<String> closedPaymentNos = new ArrayList<>();

        private RecordingPayClient() {
            super(new WechatPayProperties());
        }

        @Override
        public boolean isPaymentConfigured() {
            return true;
        }

        @Override
        public void closePayment(OrderDetailDto order) {
            closedPaymentNos.add(order.paymentOrderNo());
        }
    }

    private static final class LifecycleProvider implements ObjectProvider<LotteryOrderLifecycle> {
        private LotteryOrderLifecycle delegate;

        @Override
        public LotteryOrderLifecycle getObject() {
            return delegate;
        }

        @Override
        public LotteryOrderLifecycle getObject(Object... args) {
            return delegate;
        }

        @Override
        public LotteryOrderLifecycle getIfAvailable() {
            return delegate;
        }

        @Override
        public LotteryOrderLifecycle getIfUnique() {
            return delegate;
        }
    }
}
