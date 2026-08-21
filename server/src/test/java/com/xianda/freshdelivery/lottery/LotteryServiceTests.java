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
import com.xianda.freshdelivery.delivery.repository.DeliveryTestDatabase;
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
    private JdbcTemplate jdbcTemplate;
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
        jdbcTemplate = DeliveryTestDatabase.create(
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
        service.saveCampaign(campaign(3, 1000, LotteryCampaignFixtures.firstThreshold(10_000, 200, 100)));
        String publicJson = new ObjectMapper().writeValueAsString(service.publicCampaign());
        assertFalse(publicJson.contains("\"weight\""));
        assertFalse(publicJson.contains("\"stockRemaining\""));
        assertFalse(publicJson.contains("probabilityBp"));
        assertEquals(4, service.publicCampaign().prizes().size());
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
        service.saveCampaign(campaign(3, null, LotteryCampaignFixtures.onlyNone()));
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
                List.of(
                        LotteryCampaignFixtures.threshold(LotteryModels.PrizeCode.FIRST, 5000, 200, 100),
                        LotteryCampaignFixtures.inactive(LotteryModels.PrizeCode.SECOND),
                        LotteryCampaignFixtures.inactive(LotteryModels.PrizeCode.THIRD),
                        LotteryCampaignFixtures.thankYou(5000)
                )
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
    void thresholdMissExitsTheCandidateAndCancellationKeepsDailyLimit() {
        service.saveCampaign(campaign(2, null, List.of(
                LotteryCampaignFixtures.threshold(LotteryModels.PrizeCode.FIRST, 5000, 99_000, 800),
                LotteryCampaignFixtures.inactive(LotteryModels.PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(LotteryModels.PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(5000)
        )));
        OrderDetailDto first = createOrder();
        OrderDetailDto second = createOrder();
        OrderDetailDto third = createOrder();

        DrawResult fallback = triggerAndDraw(first);
        assertEquals("NONE", fallback.prizeType());
        assertEquals("NONE", fallback.prizeCode());
        assertEquals(0, fallback.discountAmount());
        assertEquals(4, service.orderState(first.id()).prizes().size());

        triggerAndDraw(second);
        storefront.cancelOrder(first.id(), false);
        service.onOrderCancelled(first.id(), "TEST_CANCEL");
        assertFalse(service.orderState(third.id()).eligible(), "取消不返还每日抽奖次数");
    }

    @Test
    void savingCampaignDoesNotTouchLegacyGiftStock() {
        service.saveCampaign(campaign(2, null, LotteryCampaignFixtures.onlyNone()));
        OrderDetailDto order = createOrder();
        LotteryCampaignFixtures.seedHistoricalGoodsDraw(
                dao, storefront, jdbcTemplate, order, USER_ID, 105L, 3
        );
        assertEquals(2, legacyGiftStock());

        Campaign saved = service.saveCampaign(service.adminCampaign());
        assertEquals(4, saved.prizes().size());
        assertEquals(2, legacyGiftStock());
    }

    @Test
    void activeFriendPaymentLinkBlocksDrawAndDrawnPriceFlowsToShare() {
        service.saveCampaign(campaign(3, null, LotteryCampaignFixtures.firstThreshold(10_000, 200, 80)));
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
        service.saveCampaign(campaign(2, null, LotteryCampaignFixtures.onlyNone()));
        BigDecimal stockBefore = storefront.product(104L).stockQty();
        OrderDetailDto order = createOrder();
        DrawResult result = LotteryCampaignFixtures.seedHistoricalGoodsDraw(
                dao, storefront, jdbcTemplate, order, USER_ID, 104L, 1
        );
        LocalDateTime createdAt = LocalDateTime.parse(order.createdAt().replace(" ", "T"));
        LocalDateTime expiredAt = createdAt.plusHours(7);

        storefront.closeExpiredOrders(expiredAt);
        service.onOrderExpired(order.id());
        assertEquals(stockBefore, storefront.product(104L).stockQty());
        assertEquals("RELEASED", adminDraws(null, null, null).get(0).giftStockStatus());

        service.onOrderRestarting(order.id());
        OrderDetailDto restarted = storefront.restartOrder(order.id(), 3L, expiredAt);

        assertEquals("待支付", restarted.status());
        assertEquals(result.drawId(), restarted.lotteryResult().drawId());
        assertEquals(result.payableAmount(), restarted.payableAmount());
        assertEquals(stockBefore.subtract(BigDecimal.ONE), storefront.product(104L).stockQty());
        assertEquals("RESERVED", adminDraws(null, null, null).get(0).giftStockStatus());
    }

    @Test
    void fullRefundRevokesUnfulfilledGiftButKeepsFulfilledGift() {
        service.saveCampaign(campaign(2, null, LotteryCampaignFixtures.onlyNone()));
        BigDecimal stockBefore = storefront.product(104L).stockQty();
        OrderDetailDto unfulfilled = createOrder();
        LotteryCampaignFixtures.seedHistoricalGoodsDraw(
                dao, storefront, jdbcTemplate, unfulfilled, USER_ID, 104L, 2
        );

        service.onOrderFullyRefunded(unfulfilled.id(), false);
        assertEquals(stockBefore, storefront.product(104L).stockQty());
        assertEquals("VOIDED", adminDraws(null, null, null).get(0).status());

        OrderDetailDto fulfilled = createOrder();
        DrawResult fulfilledResult = LotteryCampaignFixtures.seedHistoricalGoodsDraw(
                dao, storefront, jdbcTemplate, fulfilled, USER_ID, 104L, 1
        );
        service.onOrderFulfilled(fulfilled.id());
        service.onOrderFullyRefunded(fulfilled.id(), true);

        assertEquals(stockBefore.subtract(BigDecimal.ONE), storefront.product(104L).stockQty());
        LotteryModels.AdminDraw fulfilledDraw = adminDraws(null, null, null).stream()
                .filter(draw -> draw.orderId().equals(fulfilled.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("FULFILLED", fulfilledDraw.status());
        assertEquals("SETTLED", fulfilledDraw.relationStatus(), "履约完成的流水应进入终态，不再被对账扫到");
        assertEquals("FULFILLED", fulfilledDraw.giftStockStatus());
        assertEquals(fulfilledResult.drawId(), fulfilledDraw.id());
    }

    @Test
    void incompletePercentagePrizeIsRejected() {
        Campaign invalid = campaign(1, null, List.of(
                Prize.fixed(null, LotteryModels.PrizeCode.FIRST, 1000,
                        LotteryModels.DiscountMode.PERCENTAGE, null, null, 2000, null),
                LotteryCampaignFixtures.inactive(LotteryModels.PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(LotteryModels.PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9000)
        ));

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

    private List<LotteryModels.AdminDraw> adminDraws(String keyword, String prizeType, String status) {
        return service.draws(keyword, prizeType, status, null, null, null, null).items();
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

    @Test
    void percentageDiscountUsesFloorAndCapOnPayableAmount() {
        service.saveCampaign(campaign(3, null, LotteryCampaignFixtures.firstPercentage(10_000, 2000, 3000)));
        OrderDetailDto order = createOrder();
        int expected = (int) ((long) order.payableAmount() * 2000 / 10_000);
        DrawResult result = triggerAndDraw(order);
        assertEquals("FIRST", result.prizeCode());
        assertEquals(expected, result.discountAmount());
        assertEquals(order.payableAmount() - expected, result.payableAmount());

        service.saveCampaign(campaign(3, null, LotteryCampaignFixtures.firstPercentage(10_000, 2000, 600)));
        OrderDetailDto capped = createOrder();
        DrawResult cappedResult = triggerAndDraw(capped);
        assertEquals(600, cappedResult.discountAmount());
    }

    @Test
    void thresholdBelowFloorLeavesThePrizeAndKeepsFourSlots() {
        service.saveCampaign(campaign(3, null, List.of(
                LotteryCampaignFixtures.threshold(LotteryModels.PrizeCode.FIRST, 8000, 99_000, 800),
                LotteryCampaignFixtures.threshold(LotteryModels.PrizeCode.SECOND, 1000, 200, 100),
                LotteryCampaignFixtures.inactive(LotteryModels.PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(1000)
        )));
        OrderDetailDto order = createOrder();
        assertEquals(4, service.orderState(order.id()).prizes().size());
        DrawResult result = triggerAndDraw(order);
        assertEquals("SECOND", result.prizeCode());
        assertEquals(100, result.discountAmount());
        assertEquals(1, result.prizeIndex());
    }

    @Test
    void noAvailablePrizeDoesNotConsumeChallenge() {
        service.saveCampaign(campaign(3, 10_000, LotteryCampaignFixtures.firstThreshold(10_000, 200, 100)));
        OrderDetailDto order = createOrder();
        OrderState challenge = service.challenge(order.id());
        service.shareTriggered(order.id(), challenge.challengeToken());
        service.saveCampaign(campaign(3, 50, LotteryCampaignFixtures.firstThreshold(10_000, 200, 100)));
        BusinessException blocked = assertThrows(
                BusinessException.class,
                () -> service.draw(order.id(), challenge.challengeToken())
        );
        assertTrue(blocked.getMessage().contains("可抽取"));
        OrderState after = service.orderState(order.id());
        assertEquals(LotteryModels.ReasonCode.NO_AVAILABLE_PRIZE, after.reasonCode());
        assertFalse(Boolean.TRUE.equals(after.drawn()));
        assertEquals(4, after.prizes().size());
    }

    @Test
    void globalPoolDoesNotReturnTierNotMatched() {
        service.saveCampaign(campaign(3, null, LotteryCampaignFixtures.onlyNone()));
        OrderDetailDto order = createOrder();
        assertEquals(LotteryModels.ReasonCode.ELIGIBLE, service.orderState(order.id()).reasonCode());
    }

    private Campaign campaign(Integer dailyLimit, Integer dailyBudget, List<Prize> prizes) {
        return LotteryCampaignFixtures.campaign(dailyLimit, dailyBudget, prizes);
    }

    private int legacyGiftStock() {
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT stock_remaining FROM marketing_lottery_prize WHERE type = 'GOODS' ORDER BY id DESC LIMIT 1",
                Integer.class
        );
        return remaining == null ? 0 : remaining;
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
