package com.xianda.freshdelivery.lottery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.delivery.domain.DeliveryTask;
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
import com.xianda.freshdelivery.delivery.task.OrderTaskSnapshotFactory;
import com.xianda.freshdelivery.dto.CartDto;
import com.xianda.freshdelivery.dto.CreateAddressRequest;
import com.xianda.freshdelivery.dto.CreateOrderRequest;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.PaymentShareDto;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderState;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPayClient;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class LotteryServiceTests {
    private static final long USER_ID = 91001L;
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    private StorefrontService storefront;
    private LotteryDao dao;
    private MutableClock clock;
    private SequenceSecureRandom random;
    private RecordingPayClient payClient;
    private LotteryService service;
    private Long addressId;

    @BeforeEach
    void setUp() {
        CurrentUserContext.setUserId(USER_ID);
        storefront = new StorefrontService(tempDir.resolve("storefront.json").toString(), true);
        JdbcTemplate jdbcTemplate = DeliveryTestDatabase.create(
                "lottery_" + System.nanoTime()
        );
        dao = new LotteryDao(jdbcTemplate);
        clock = new MutableClock(Instant.parse("2026-08-12T01:00:00Z"), STORE_ZONE);
        random = new SequenceSecureRandom();
        payClient = new RecordingPayClient();
        service = new LotteryService(
                dao,
                storefront,
                payClient,
                clock,
                random,
                Duration.ofMinutes(10)
        );
        addressId = storefront.createAddress(new CreateAddressRequest(
                "抽奖测试用户",
                "13800000000",
                "1号楼 101室",
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
    void challengeDrawIsIdempotentAndDiscountRotatesPaymentOrder() throws Exception {
        service.saveCampaign(campaign(3, 1000, List.of(discountPrize(100, 10))));
        String publicJson = new ObjectMapper().writeValueAsString(service.publicCampaign());
        assertFalse(publicJson.contains("\"weight\""));
        assertFalse(publicJson.contains("\"stockRemaining\""));
        assertFalse(service.publicCampaign().prizes().isEmpty());
        OrderDetailDto order = createOrder();
        String originalPaymentOrderNo = order.paymentOrderNo();

        OrderState challenge = service.challenge(order.id());
        assertNotNull(challenge.challengeToken());
        assertFalse(challenge.shareTriggered());
        OrderState shared = service.shareTriggered(order.id(), challenge.challengeToken());
        assertTrue(shared.shareTriggered());

        DrawResult first = service.draw(order.id(), challenge.challengeToken());
        DrawResult repeated = service.draw(order.id(), challenge.challengeToken());
        OrderDetailDto adjusted = storefront.order(order.id());

        assertEquals(first.drawId(), repeated.drawId());
        assertEquals("DISCOUNT", first.prizeType());
        assertEquals(100, first.discountAmount());
        assertEquals(order.payableAmount() - 100, first.payableAmount());
        assertEquals(first.payableAmount(), adjusted.payableAmount());
        assertEquals(100, adjusted.discountAmount());
        assertNotEquals(originalPaymentOrderNo, adjusted.paymentOrderNo());
        assertEquals(1, payClient.closedCount.get());
        assertEquals(first.drawId(), adjusted.lotteryResult().drawId());

        assertThrows(BusinessException.class, () -> storefront.confirmPayment(new PaymentNotifyRequest(
                adjusted.paymentOrderNo(),
                "TX-OLD-AMOUNT",
                "SUCCESS",
                "wx-test",
                "mch-test",
                order.payableAmount()
        )));
        assertEquals(first.payableAmount(), storefront.confirmPayment(new PaymentNotifyRequest(
                adjusted.paymentOrderNo(),
                "TX-DISCOUNTED-AMOUNT",
                "SUCCESS",
                "wx-test",
                "mch-test",
                first.payableAmount()
        )).order().paidAmount());
    }

    @Test
    void challengeIsBoundToUserAndOrderAndExpires() {
        service.saveCampaign(campaign(3, null, List.of(nonePrize(10))));
        OrderDetailDto first = createOrder();
        OrderDetailDto second = createOrder();
        OrderState challenge = service.challenge(first.id());

        assertThrows(
                BusinessException.class,
                () -> service.shareTriggered(second.id(), challenge.challengeToken())
        );
        CurrentUserContext.setUserId(USER_ID + 1);
        assertThrows(
                BusinessException.class,
                () -> service.shareTriggered(first.id(), challenge.challengeToken())
        );

        CurrentUserContext.setUserId(USER_ID);
        clock.advance(Duration.ofMinutes(11));
        assertThrows(
                BusinessException.class,
                () -> service.shareTriggered(first.id(), challenge.challengeToken())
        );
    }

    @Test
    void dailyBudgetFiltersDiscountAndDailyLimitStillCountsNone() {
        service.saveCampaign(campaign(
                2,
                100,
                List.of(discountPrize(100, 10), nonePrize(20))
        ));
        OrderDetailDto first = createOrder();
        OrderDetailDto second = createOrder();
        OrderDetailDto third = createOrder();

        assertEquals("DISCOUNT", triggerAndDraw(first).prizeType());
        assertEquals("NONE", triggerAndDraw(second).prizeType());

        OrderState thirdState = service.orderState(third.id());
        assertFalse(thirdState.eligible());
        assertTrue(thirdState.reason().contains("次数"));
    }

    @Test
    void exhaustedGiftFallsBackAndCancellationRestoresBothStocksWithoutChance() {
        service.saveCampaign(campaign(
                2,
                null,
                List.of(goodsPrize(105L, 1, 10), nonePrize(20))
        ));
        BigDecimal giftStockBefore = storefront.product(105L).stockQty();
        OrderDetailDto first = createOrder();
        OrderDetailDto second = createOrder();
        OrderDetailDto third = createOrder();

        DrawResult gift = triggerAndDraw(first);
        assertEquals("GOODS", gift.prizeType());
        assertEquals(giftStockBefore.subtract(BigDecimal.ONE), storefront.product(105L).stockQty());
        DeliveryTask task = new OrderTaskSnapshotFactory().build(
                storefront.order(first.id()),
                null,
                "PS-LOTTERY-TEST",
                LocalDateTime.now(),
                null,
                null
        );
        assertEquals(0, task.marketingDiscountAmount());
        assertTrue(task.marketingGiftSummary().contains(gift.gifts().get(0).productName()));
        assertTrue(task.goodsSummary().contains("赠"));
        assertEquals(1, service.draws(null, "GOODS", "PENDING").size());
        assertNotNull(service.draws(null, "GOODS", "PENDING").get(0).drawnAt());

        DrawResult fallback = triggerAndDraw(second);
        assertEquals("NONE", fallback.prizeType());
        Prize configuredGift = service.adminCampaign().tiers().get(0).prizes().stream()
                .filter(prize -> "GOODS".equals(prize.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, configuredGift.stockRemaining());

        storefront.cancelOrder(first.id(), false);
        service.onOrderCancelled(first.id(), "TEST_CANCEL");

        assertEquals(giftStockBefore, storefront.product(105L).stockQty());
        Prize restoredGift = service.adminCampaign().tiers().get(0).prizes().stream()
                .filter(prize -> "GOODS".equals(prize.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, restoredGift.stockRemaining());
        assertEquals("VOIDED", service.draws(null, null, null).stream()
                .filter(draw -> draw.orderId().equals(first.id()))
                .findFirst().orElseThrow().status());
        assertFalse(service.orderState(third.id()).eligible(), "取消不返还每日抽奖次数");
    }

    @Test
    void savingStaleCampaignDoesNotRestoreConsumedGiftStock() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(105L, 3, 10))));
        triggerAndDraw(createOrder());

        Campaign current = service.adminCampaign();
        Tier currentTier = current.tiers().get(0);
        Prize currentPrize = currentTier.prizes().get(0);
        assertEquals(2, currentPrize.stockRemaining());

        Prize stalePrize = new Prize(
                currentPrize.id(),
                currentPrize.type(),
                currentPrize.name(),
                currentPrize.discountAmount(),
                currentPrize.productId(),
                currentPrize.skuId(),
                currentPrize.imageUrl(),
                currentPrize.weight(),
                currentPrize.stockTotal(),
                currentPrize.stockTotal(),
                currentPrize.enabled(),
                currentPrize.sortOrder()
        );
        Campaign staleSave = new Campaign(
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
                        currentTier.id(),
                        currentTier.name(),
                        currentTier.minProductAmount(),
                        currentTier.maxProductAmount(),
                        currentTier.enabled(),
                        currentTier.sortOrder(),
                        List.of(stalePrize)
                ))
        );

        Campaign saved = service.saveCampaign(staleSave);

        assertEquals(2, saved.tiers().get(0).prizes().get(0).stockRemaining());
    }

    @Test
    void activeFriendPaymentLinkBlocksDrawAndDrawnPriceFlowsToShare() {
        service.saveCampaign(campaign(3, null, List.of(discountPrize(80, 10))));
        OrderDetailDto blockedOrder = createOrder();
        OrderState challenge = service.challenge(blockedOrder.id());
        service.shareTriggered(blockedOrder.id(), challenge.challengeToken());
        storefront.createPaymentShare(blockedOrder.id());

        BusinessException blocked = assertThrows(
                BusinessException.class,
                () -> service.draw(blockedOrder.id(), challenge.challengeToken())
        );
        assertTrue(blocked.getMessage().contains("代付"));

        storefront.activateSelfPayment(blockedOrder.id());
        OrderDetailDto drawnOrder = createOrder();
        DrawResult result = triggerAndDraw(drawnOrder);
        PaymentShareDto share = storefront.createPaymentShare(drawnOrder.id());

        assertEquals(result.payableAmount(), share.payableAmount());
        assertEquals(result.discountAmount(), share.discountAmount());
        assertEquals(result.drawId(), share.lotteryResult().drawId());
    }

    @Test
    void expiredOrderReleasesGiftStockAndRestartKeepsOriginalPrize() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 1, 10))));
        BigDecimal stockBefore = storefront.product(104L).stockQty();
        OrderDetailDto order = createOrder();
        DrawResult result = triggerAndDraw(order);
        LocalDateTime createdAt = LocalDateTime.parse(order.createdAt().replace(" ", "T"));
        LocalDateTime expiredAt = createdAt.plusHours(7);

        storefront.closeExpiredOrders(expiredAt);
        service.onOrderExpired(order.id());
        assertEquals(stockBefore, storefront.product(104L).stockQty());
        assertEquals("RELEASED", service.draws(null, null, null).get(0).giftStockStatus());

        service.onOrderRestarting(order.id());
        OrderDetailDto restarted = storefront.restartOrder(order.id(), 3L, expiredAt);

        assertEquals("待支付", restarted.status());
        assertEquals(result.drawId(), restarted.lotteryResult().drawId());
        assertEquals(result.payableAmount(), restarted.payableAmount());
        assertEquals(stockBefore.subtract(BigDecimal.ONE), storefront.product(104L).stockQty());
        assertEquals("RESERVED", service.draws(null, null, null).get(0).giftStockStatus());
    }

    @Test
    void fullRefundRevokesUnfulfilledGiftButKeepsFulfilledGift() {
        service.saveCampaign(campaign(2, null, List.of(goodsPrize(104L, 2, 10))));
        BigDecimal stockBefore = storefront.product(104L).stockQty();
        OrderDetailDto unfulfilled = createOrder();
        triggerAndDraw(unfulfilled);

        service.onOrderFullyRefunded(unfulfilled.id(), false);
        assertEquals(stockBefore, storefront.product(104L).stockQty());
        assertEquals("VOIDED", service.draws(null, null, null).get(0).status());

        OrderDetailDto fulfilled = createOrder();
        triggerAndDraw(fulfilled);
        service.onOrderFulfilled(fulfilled.id());
        service.onOrderFullyRefunded(fulfilled.id(), true);

        assertEquals(stockBefore.subtract(BigDecimal.ONE), storefront.product(104L).stockQty());
        LotteryModels.AdminDraw fulfilledDraw = service.draws(null, null, null).stream()
                .filter(draw -> draw.orderId().equals(fulfilled.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("FULFILLED", fulfilledDraw.status());
        assertEquals("APPLIED", fulfilledDraw.relationStatus());
        assertEquals("FULFILLED", fulfilledDraw.giftStockStatus());
    }

    @Test
    void goodsConfigurationMustReferenceARealProductSkuPair() {
        Campaign invalid = campaign(1, null, List.of(new Prize(
                null,
                "GOODS",
                "错误 SKU 赠品",
                null,
                106L,
                999999L,
                null,
                1,
                1,
                null,
                true,
                10
        )));

        assertThrows(BusinessException.class, () -> service.saveCampaign(invalid));
    }

    @Test
    void storefrontLoadsSnapshotWrittenBeforePromotionProjectionField() throws Exception {
        OrderDetailDto order = createOrder();
        Path statePath = tempDir.resolve("storefront.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode snapshot = mapper.readTree(Files.readString(statePath));
        ((ObjectNode) snapshot).remove("orderPromotions");
        Files.writeString(statePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));

        StorefrontService reloaded = new StorefrontService(statePath.toString(), false);
        CurrentUserContext.setUserId(USER_ID);
        OrderDetailDto loaded = reloaded.order(order.id());

        assertEquals(order.payableAmount(), loaded.payableAmount());
        assertEquals(0, loaded.discountAmount());
        assertTrue(loaded.gifts().isEmpty());
        assertEquals(null, loaded.lotteryResult());
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

    private Campaign campaign(Integer dailyLimit, Integer dailyBudget, List<Prize> prizes) {
        return new Campaign(
                null,
                true,
                "随机减免测试",
                "2026-08-01T00:00:00",
                "2026-09-01T00:00:00",
                dailyLimit,
                dailyBudget,
                "分享后抽奖",
                "仅记录朋友圈菜单触发，不验证最终发布",
                "/lottery.png",
                List.of(new Tier(null, "全金额", 0, null, true, 10, prizes))
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

    private static final class SequenceSecureRandom extends SecureRandom {
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
        private final AtomicInteger closedCount = new AtomicInteger();

        private RecordingPayClient() {
            super(new WechatPayProperties());
        }

        @Override
        public boolean isPaymentConfigured() {
            return true;
        }

        @Override
        public void closePayment(OrderDetailDto order) {
            closedCount.incrementAndGet();
        }
    }
}
