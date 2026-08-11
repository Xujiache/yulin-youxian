package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.lottery.LotteryDao.ChallengeRow;
import com.xianda.freshdelivery.lottery.LotteryDao.DecisionRow;
import com.xianda.freshdelivery.lottery.LotteryDao.DrawInsert;
import com.xianda.freshdelivery.lottery.LotteryDao.DrawRow;
import com.xianda.freshdelivery.lottery.LotteryModels.AdminDraw;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderPromotionProjection;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderState;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeType;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicCampaign;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicPrize;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicTier;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPayClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LotteryService implements LotteryOrderLifecycle {
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final LotteryDao dao;
    private final StorefrontService storefrontService;
    private final WechatPayClient wechatPayClient;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Duration challengeTtl;

    @Autowired
    public LotteryService(
            LotteryDao dao,
            StorefrontService storefrontService,
            WechatPayClient wechatPayClient,
            @Value("${marketing.lottery.challenge-ttl-seconds:600}") long challengeTtlSeconds
    ) {
        this(
                dao,
                storefrontService,
                wechatPayClient,
                Clock.system(STORE_ZONE),
                new SecureRandom(),
                Duration.ofSeconds(Math.max(challengeTtlSeconds, 30))
        );
    }

    public LotteryService(
            LotteryDao dao,
            StorefrontService storefrontService,
            WechatPayClient wechatPayClient,
            Clock clock,
            SecureRandom secureRandom,
            Duration challengeTtl
    ) {
        this.dao = dao;
        this.storefrontService = storefrontService;
        this.wechatPayClient = wechatPayClient;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.challengeTtl = challengeTtl;
    }

    public Campaign adminCampaign() {
        return dao.findCampaign().orElseGet(this::emptyCampaign);
    }

    public PublicCampaign publicCampaign() {
        return toPublic(adminCampaign());
    }

    @Transactional
    public Campaign saveCampaign(Campaign request) {
        Campaign normalized = normalizeCampaign(request);
        LotteryRules.validate(normalized);
        normalized.tiers().stream()
                .flatMap(tier -> tier.prizes().stream())
                .filter(prize -> LotteryRules.prizeType(prize.type()) == PrizeType.GOODS)
                .forEach(prize -> storefrontService.validateLotteryGift(prize.productId(), prize.skuId()));
        return dao.saveCampaign(normalized);
    }

    public OrderState orderState(long orderId) {
        long userId = CurrentUserContext.userId();
        return orderState(orderId, userId, now());
    }

    @Transactional
    public OrderState challenge(long orderId) {
        long userId = CurrentUserContext.userId();
        LocalDateTime now = now();
        OrderState state = orderState(orderId, userId, now);
        if (!Boolean.TRUE.equals(state.eligible())) {
            throw new BusinessException(409, state.reason());
        }
        Campaign campaign = requiredCampaign();
        dao.lockCampaign(campaign.id());
        campaign = requiredCampaign();
        OrderState lockedState = orderState(orderId, userId, now);
        if (!Boolean.TRUE.equals(lockedState.eligible())) {
            throw new BusinessException(409, lockedState.reason());
        }
        long campaignId = campaign.id();
        ChallengeRow challenge = dao.findActiveChallenge(campaignId, orderId, userId, now)
                .orElseGet(() -> dao.createChallenge(
                        campaignId,
                        orderId,
                        userId,
                        challengeToken(),
                        now,
                        now.plus(challengeTtl)
                ));
        return orderState(orderId, userId, now, challenge);
    }

    @Transactional
    public OrderState shareTriggered(long orderId, String challengeToken) {
        long userId = CurrentUserContext.userId();
        LocalDateTime now = now();
        DrawRow existingDraw = dao.findDrawByOrder(orderId).orElse(null);
        if (existingDraw != null && existingDraw.userId() == userId) {
            return orderState(orderId, userId, now);
        }
        ChallengeRow challenge = requireChallenge(challengeToken, orderId, userId, now);
        if (dao.markShareTriggered(challenge.id(), now) != 1) {
            throw new BusinessException(409, "分享挑战已过期，请重新获取");
        }
        ChallengeRow updated = dao.findChallenge(challenge.token(), orderId, userId).orElseThrow();
        return orderState(orderId, userId, now, updated);
    }

    @Transactional
    public DrawResult draw(long orderId, String challengeToken) {
        long userId = CurrentUserContext.userId();
        DrawRow existing = dao.findDrawByOrder(orderId).orElse(null);
        if (existing != null) {
            if (existing.userId() != userId) {
                throw new BusinessException(404, "订单不存在");
            }
            return toResult(existing);
        }

        LocalDateTime now = now();
        ChallengeRow challenge = requireChallenge(challengeToken, orderId, userId, now);
        if (challenge.shareTriggeredAt() == null) {
            throw new BusinessException(409, "请先通过朋友圈菜单触发分享资格");
        }
        OrderDetailDto order = storefrontService.lotteryOrder(orderId, userId);
        if (!"待支付".equals(order.status())) {
            throw new BusinessException(409, "仅待支付订单可以抽奖");
        }
        if (storefrontService.hasActivePaymentShare(orderId)) {
            throw new BusinessException(409, "已有有效好友代付链接，不能再抽奖");
        }
        DecisionRow decision = dao.findDecision(orderId).orElse(null);
        if (decision != null && "SKIPPED".equals(decision.decision())) {
            throw new BusinessException(409, "订单已跳过抽奖并锁定价格");
        }

        Campaign campaign = requiredCampaign();
        if (challenge.campaignId() != campaign.id()) {
            throw new BusinessException(409, "活动配置已变化，请重新获取分享挑战");
        }
        dao.lockCampaign(campaign.id());
        campaign = requiredCampaign();
        ensureCampaignActive(campaign, now);
        if (challenge.campaignId() != campaign.id()) {
            throw new BusinessException(409, "活动配置已变化，请重新获取分享挑战");
        }
        DrawRow concurrentDraw = dao.findDrawByOrder(orderId).orElse(null);
        if (concurrentDraw != null) {
            return toResult(concurrentDraw);
        }
        Tier tier = LotteryRules.matchingTier(campaign, order.productAmount());
        if (tier == null) {
            throw new BusinessException(409, "订单商品金额不在活动阶梯内");
        }
        ensureDailyLimit(campaign, userId, now);
        int spent = dailyDiscountSpent(campaign, now);
        List<Prize> candidates = new ArrayList<>(eligiblePrizes(campaign, tier, order, spent));
        if (candidates.isEmpty()) {
            throw new BusinessException(409, "当前阶梯暂无可抽取奖项");
        }

        Prize selected;
        boolean marketingStockReserved = false;
        while (true) {
            selected = LotteryRules.weightedPrize(candidates, secureRandom);
            if (LotteryRules.prizeType(selected.type()) != PrizeType.GOODS) {
                break;
            }
            if (dao.reserveMarketingStock(selected.id()) == 1) {
                marketingStockReserved = true;
                break;
            }
            Prize unavailable = selected;
            candidates.removeIf(prize -> Objects.equals(prize.id(), unavailable.id()));
            if (candidates.isEmpty()) {
                throw new BusinessException(409, "奖品库存已耗尽");
            }
        }

        PrizeType type = LotteryRules.prizeType(selected.type());
        int discountAmount = type == PrizeType.DISCOUNT
                ? Math.min(selected.discountAmount(), Math.max(order.payableAmount() - 1, 0))
                : 0;
        int payableAmount = Math.max(1, order.payableAmount() - discountAmount);
        if (type == PrizeType.DISCOUNT && wechatPayClient != null && wechatPayClient.isPaymentConfigured()) {
            wechatPayClient.closePayment(order);
        }

        int prizeIndex = tier.prizes().indexOf(selected);
        Gift giftTemplate = type == PrizeType.GOODS
                ? storefrontService.lotteryGift(
                        selected.productId(),
                        selected.skuId(),
                        null,
                        selected.id(),
                        selected.imageUrl()
                )
                : null;
        DrawInsert insert = new DrawInsert(
                campaign.id(),
                tier.id(),
                selected.id(),
                order.id(),
                order.orderNo(),
                userId,
                order.productAmount(),
                prizeIndex,
                type.name(),
                selected.name(),
                discountAmount,
                selected.productId(),
                selected.skuId(),
                selected.imageUrl(),
                order.payableAmount(),
                payableAmount,
                giftTemplate
        );
        long drawId = 0;
        try {
            drawId = dao.insertDraw(insert, now);
            Gift gift = giftTemplate == null ? null : new Gift(
                    drawId,
                    selected.id(),
                    giftTemplate.productId(),
                    giftTemplate.skuId(),
                    giftTemplate.productName(),
                    giftTemplate.skuName(),
                    giftTemplate.imageUrl(),
                    giftTemplate.quantity(),
                    "RESERVED"
            );
            if (gift != null) {
                dao.insertGift(drawId, selected.id(), orderId, gift, now);
            }
            OrderPromotionProjection projection = new OrderPromotionProjection(
                    drawId,
                    selected.id(),
                    prizeIndex,
                    type.name(),
                    selected.name(),
                    discountAmount,
                    selected.productId(),
                    selected.skuId(),
                    selected.imageUrl(),
                    payableAmount,
                    gift == null ? List.of() : List.of(gift),
                    "APPLIED"
            );
            storefrontService.applyLotteryPromotion(orderId, userId, projection);
            dao.markDrawApplied(drawId, now);
            dao.saveDecision(orderId, userId, campaign.id(), "DRAWN", drawId, now);
            dao.consumeChallenge(challenge.id(), now);
            return projection.toResult();
        } catch (RuntimeException exception) {
            if (drawId > 0) {
                try {
                    storefrontService.rollbackLotteryPromotion(orderId, drawId, order.payableAmount());
                } catch (RuntimeException compensationException) {
                    exception.addSuppressed(compensationException);
                }
            }
            if (marketingStockReserved && selected.id() != null) {
                try {
                    dao.restoreMarketingStock(selected.id());
                } catch (RuntimeException compensationException) {
                    exception.addSuppressed(compensationException);
                }
            }
            throw exception;
        }
    }

    public List<AdminDraw> draws(String keyword, String prizeType, String status) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        String relationStatus = List.of("RESERVED", "APPLIED", "VOIDED").contains(normalizedStatus)
                ? normalizedStatus
                : null;
        return dao.searchDraws(keyword, prizeType, relationStatus).stream()
                .map(this::toAdminDraw)
                .filter(draw -> normalizedStatus.isBlank()
                        || normalizedStatus.equals(draw.status())
                        || normalizedStatus.equals(draw.relationStatus()))
                .toList();
    }

    @Transactional
    public AdminDraw fulfill(long drawId, String remark) {
        DrawRow draw = dao.findDraw(drawId)
                .orElseThrow(() -> new BusinessException(404, "抽奖流水不存在"));
        if (!"GOODS".equals(draw.prizeType())) {
            throw new BusinessException(409, "仅赠品奖项需要履约确认");
        }
        if ("VOIDED".equals(draw.status()) || "RELEASED".equals(draw.giftStockStatus())) {
            throw new BusinessException(409, "该赠品已撤销或释放");
        }
        if (!"FULFILLED".equals(draw.giftStockStatus())) {
            storefrontService.fulfillLotteryPromotion(draw.orderId());
            dao.fulfillDraw(draw.id(), trimToNull(remark), now());
        }
        return toAdminDraw(dao.findDraw(drawId).orElseThrow());
    }

    @Override
    @Transactional
    public void lockForPaymentShare(long orderId, long userId) {
        storefrontService.lotteryOrder(orderId, userId);
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        Campaign campaign = requiredCampaign();
        if (draw != null) {
            dao.saveDecision(orderId, userId, campaign.id(), "DRAWN", draw.id(), now());
            return;
        }
        DecisionRow existing = dao.findDecision(orderId).orElse(null);
        if (existing == null) {
            dao.saveDecision(orderId, userId, campaign.id(), "SKIPPED", null, now());
        }
    }

    @Override
    @Transactional
    public void onOrderCancelled(long orderId, String reason) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || "VOIDED".equals(draw.status())) {
            return;
        }
        releaseGiftStockIfNeeded(draw);
        storefrontService.releaseLotteryPromotion(orderId, true);
        dao.voidDraw(draw.id(), reason, now());
    }

    @Override
    @Transactional
    public void onOrderExpired(long orderId) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || !"APPLIED".equals(draw.status())
                || !"RESERVED".equals(draw.giftStockStatus())) {
            return;
        }
        storefrontService.releaseLotteryPromotion(orderId, false);
        dao.restoreMarketingStock(draw.prizeId());
        dao.releaseGift(draw.id(), now());
    }

    @Override
    @Transactional
    public void onOrderRestarting(long orderId) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || !"APPLIED".equals(draw.status())
                || !"RELEASED".equals(draw.giftStockStatus())) {
            return;
        }
        if (dao.reserveMarketingStock(draw.prizeId()) != 1) {
            throw new BusinessException(409, "赠品营销库存不足，暂时无法重启订单");
        }
        try {
            storefrontService.reserveLotteryPromotion(orderId);
            dao.reserveReleasedGift(draw.id(), now());
        } catch (RuntimeException exception) {
            dao.restoreMarketingStock(draw.prizeId());
            throw exception;
        }
    }

    @Override
    @Transactional
    public void onOrderRestartFailed(long orderId) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || !"RESERVED".equals(draw.giftStockStatus())) {
            return;
        }
        storefrontService.releaseLotteryPromotion(orderId, false);
        dao.restoreMarketingStock(draw.prizeId());
        dao.releaseGift(draw.id(), now());
    }

    @Override
    @Transactional
    public void onOrderFulfilled(long orderId) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || !"APPLIED".equals(draw.status())
                || !"RESERVED".equals(draw.giftStockStatus())) {
            return;
        }
        storefrontService.fulfillLotteryPromotion(orderId);
        dao.fulfillDraw(draw.id(), "随订单履约完成", now());
    }

    @Override
    @Transactional
    public void onOrderFullyRefunded(long orderId, boolean orderAlreadyFulfilled) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || "VOIDED".equals(draw.status())) {
            return;
        }
        if (orderAlreadyFulfilled) {
            if ("RESERVED".equals(draw.giftStockStatus())) {
                storefrontService.fulfillLotteryPromotion(orderId);
                dao.fulfillDraw(draw.id(), "订单已履约，退款保留赠品", now());
            }
            return;
        }
        if ("FULFILLED".equals(draw.giftStockStatus())) {
            return;
        }
        releaseGiftStockIfNeeded(draw);
        storefrontService.releaseLotteryPromotion(orderId, true);
        dao.voidDraw(draw.id(), "FULL_REFUND_BEFORE_FULFILLMENT", now());
    }

    @Scheduled(
            fixedDelayString = "${marketing.lottery.consistency-scan-ms:300000}",
            initialDelayString = "${marketing.lottery.consistency-initial-delay-ms:30000}"
    )
    @Transactional
    public void reconcile() {
        LocalDateTime now = now();
        storefrontService.lotteryPromotions().forEach((orderId, projection) -> {
            if (projection.drawId() != null && dao.findDraw(projection.drawId()).isEmpty()) {
                int payableBefore = value(projection.payableAmount(), 1)
                        + value(projection.discountAmount(), 0);
                storefrontService.rollbackLotteryPromotion(orderId, projection.drawId(), payableBefore);
            }
        });
        for (DrawRow draw : dao.findDrawsWithStatus("RESERVED")) {
            OrderPromotionProjection projection = storefrontService.lotteryPromotion(draw.orderId());
            if (projection != null && Objects.equals(projection.drawId(), draw.id())) {
                dao.markDrawApplied(draw.id(), now);
                dao.saveDecision(draw.orderId(), draw.userId(), draw.campaignId(), "DRAWN", draw.id(), now);
            } else {
                if ("RESERVED".equals(draw.giftStockStatus())) {
                    dao.restoreMarketingStock(draw.prizeId());
                    dao.releaseGift(draw.id(), now);
                }
                dao.voidDraw(draw.id(), "RESERVED_WITHOUT_STOREFRONT_PROJECTION", now);
            }
        }
        for (DrawRow draw : dao.findDrawsWithStatus("APPLIED")) {
            OrderPromotionProjection projection = storefrontService.lotteryPromotion(draw.orderId());
            if (projection == null) {
                try {
                    storefrontService.repairLotteryProjection(draw.orderId(), toProjection(draw));
                } catch (BusinessException exception) {
                    if ("RESERVED".equals(draw.giftStockStatus())) {
                        dao.restoreMarketingStock(draw.prizeId());
                        dao.releaseGift(draw.id(), now);
                    }
                    dao.voidDraw(draw.id(), "PROJECTION_REPAIR_FAILED", now);
                    continue;
                }
                projection = storefrontService.lotteryPromotion(draw.orderId());
            }
            if (projection != null && "VOIDED".equals(projection.status())) {
                if ("RESERVED".equals(draw.giftStockStatus())) {
                    dao.restoreMarketingStock(draw.prizeId());
                    dao.releaseGift(draw.id(), now);
                }
                dao.voidDraw(draw.id(), "STOREFRONT_PROJECTION_VOIDED", now);
                continue;
            }
            OrderDetailDto order;
            try {
                order = storefrontService.lotteryOrder(draw.orderId(), draw.userId());
            } catch (BusinessException exception) {
                continue;
            }
            if ("RELEASED".equals(draw.giftStockStatus())
                    && projection != null
                    && projection.gifts().stream().anyMatch(gift -> "RESERVED".equals(gift.status()))) {
                storefrontService.releaseLotteryPromotion(draw.orderId(), false);
                projection = storefrontService.lotteryPromotion(draw.orderId());
            } else if ("FULFILLED".equals(draw.giftStockStatus())
                    && projection != null
                    && projection.gifts().stream().anyMatch(gift -> !"FULFILLED".equals(gift.status()))) {
                storefrontService.fulfillLotteryPromotion(draw.orderId());
                projection = storefrontService.lotteryPromotion(draw.orderId());
            }
            if ("已取消".equals(order.status())) {
                onOrderCancelled(draw.orderId(), "CONSISTENCY_CANCELLED");
            } else if ("已关闭".equals(order.status())) {
                onOrderExpired(draw.orderId());
            } else if ("已完成".equals(order.status())) {
                onOrderFulfilled(draw.orderId());
            } else if ("已退款".equals(order.status())) {
                onOrderFullyRefunded(draw.orderId(), false);
            }
        }
    }

    private OrderState orderState(long orderId, long userId, LocalDateTime now) {
        Campaign campaign = dao.findCampaign().orElseGet(this::emptyCampaign);
        ChallengeRow challenge = campaign.id() == null
                ? null
                : dao.findActiveChallenge(campaign.id(), orderId, userId, now).orElse(null);
        return orderState(orderId, userId, now, challenge);
    }

    private OrderState orderState(
            long orderId,
            long userId,
            LocalDateTime now,
            ChallengeRow challenge
    ) {
        OrderDetailDto order = storefrontService.lotteryOrder(orderId, userId);
        Campaign campaign = dao.findCampaign().orElseGet(this::emptyCampaign);
        PublicCampaign publicCampaign = toPublic(campaign);
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw != null) {
            return new OrderState(
                    false,
                    "该订单已抽奖",
                    null,
                    true,
                    true,
                    publicCampaign,
                    publicPrizes(campaign, order.productAmount()),
                    toResult(draw)
            );
        }
        DecisionRow decision = dao.findDecision(orderId).orElse(null);
        if (decision != null && "SKIPPED".equals(decision.decision())) {
            return unavailable(publicCampaign, campaign, order, "订单已跳过抽奖并锁定价格", challenge);
        }
        if (!"待支付".equals(order.status())) {
            return unavailable(publicCampaign, campaign, order, "仅待支付订单可以抽奖", challenge);
        }
        String campaignReason = campaignUnavailableReason(campaign, now);
        if (campaignReason != null) {
            return unavailable(publicCampaign, campaign, order, campaignReason, challenge);
        }
        if (storefrontService.hasActivePaymentShare(orderId)) {
            return unavailable(publicCampaign, campaign, order, "已有有效好友代付链接，不能再抽奖", challenge);
        }
        Tier tier = LotteryRules.matchingTier(campaign, order.productAmount());
        if (tier == null) {
            return unavailable(publicCampaign, campaign, order, "订单商品金额不在活动阶梯内", challenge);
        }
        if (dao.countUserDraws(
                campaign.id(),
                userId,
                startOfDay(now),
                startOfDay(now).plusDays(1)
        ) >= campaign.dailyUserLimit()) {
            return unavailable(publicCampaign, campaign, order, "今日抽奖次数已用完", challenge);
        }
        List<Prize> eligible = eligiblePrizes(campaign, tier, order, dailyDiscountSpent(campaign, now));
        if (eligible.isEmpty()) {
            return unavailable(publicCampaign, campaign, order, "当前阶梯暂无可抽取奖项", challenge);
        }
        return new OrderState(
                true,
                "",
                challenge == null ? null : challenge.token(),
                challenge != null && challenge.shareTriggeredAt() != null,
                false,
                publicCampaign,
                tier.prizes().stream()
                        .filter(prize -> !Boolean.FALSE.equals(prize.enabled()))
                        .map(this::toPublic)
                        .toList(),
                null
        );
    }

    private OrderState unavailable(
            PublicCampaign publicCampaign,
            Campaign campaign,
            OrderDetailDto order,
            String reason,
            ChallengeRow challenge
    ) {
        return new OrderState(
                false,
                reason,
                challenge == null ? null : challenge.token(),
                challenge != null && challenge.shareTriggeredAt() != null,
                false,
                publicCampaign,
                publicPrizes(campaign, order.productAmount()),
                null
        );
    }

    private List<Prize> eligiblePrizes(
            Campaign campaign,
            Tier tier,
            OrderDetailDto order,
            int spent
    ) {
        int budget = campaign.dailyBudgetAmount() == null
                ? Integer.MAX_VALUE
                : campaign.dailyBudgetAmount();
        return tier.prizes().stream()
                .filter(prize -> !Boolean.FALSE.equals(prize.enabled()))
                .filter(prize -> {
                    PrizeType type = LotteryRules.prizeType(prize.type());
                    if (type == PrizeType.GOODS) {
                        return prize.stockRemaining() != null
                                && prize.stockRemaining() > 0
                                && storefrontService.canReserveLotteryGift(prize.productId(), prize.skuId());
                    }
                    if (type == PrizeType.DISCOUNT) {
                        int actual = Math.min(prize.discountAmount(), Math.max(order.payableAmount() - 1, 0));
                        return actual > 0 && (long) spent + actual <= budget;
                    }
                    return true;
                })
                .sorted(Comparator
                        .comparingInt((Prize prize) -> prize.sortOrder() == null ? 100 : prize.sortOrder())
                        .thenComparing(prize -> prize.id() == null ? Long.MAX_VALUE : prize.id()))
                .toList();
    }

    private void ensureDailyLimit(Campaign campaign, long userId, LocalDateTime now) {
        if (dao.countUserDraws(
                campaign.id(),
                userId,
                startOfDay(now),
                startOfDay(now).plusDays(1)
        ) >= campaign.dailyUserLimit()) {
            throw new BusinessException(409, "今日抽奖次数已用完");
        }
    }

    private int dailyDiscountSpent(Campaign campaign, LocalDateTime now) {
        return dao.sumDailyDiscount(
                campaign.id(),
                startOfDay(now),
                startOfDay(now).plusDays(1)
        );
    }

    private LocalDateTime startOfDay(LocalDateTime now) {
        return now.toLocalDate().atStartOfDay();
    }

    private ChallengeRow requireChallenge(
            String token,
            long orderId,
            long userId,
            LocalDateTime now
    ) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(400, "challengeToken 不能为空");
        }
        ChallengeRow challenge = dao.findChallenge(token.trim(), orderId, userId)
                .orElseThrow(() -> new BusinessException(409, "分享挑战无效"));
        if (challenge.consumedAt() != null) {
            throw new BusinessException(409, "分享挑战已使用");
        }
        if (!challenge.expiresAt().isAfter(now)) {
            throw new BusinessException(409, "分享挑战已过期，请重新获取");
        }
        return challenge;
    }

    private void releaseGiftStockIfNeeded(DrawRow draw) {
        if (!"RESERVED".equals(draw.giftStockStatus())) {
            return;
        }
        dao.restoreMarketingStock(draw.prizeId());
        dao.releaseGift(draw.id(), now());
    }

    private DrawResult toResult(DrawRow draw) {
        return new DrawResult(
                draw.id(),
                draw.prizeId(),
                draw.prizeIndex(),
                draw.prizeType(),
                draw.prizeName(),
                draw.discountAmount(),
                draw.productId(),
                draw.skuId(),
                draw.imageUrl(),
                draw.payableAmount(),
                dao.findGifts(draw.id()),
                draw.status()
        );
    }

    private OrderPromotionProjection toProjection(DrawRow draw) {
        return new OrderPromotionProjection(
                draw.id(),
                draw.prizeId(),
                draw.prizeIndex(),
                draw.prizeType(),
                draw.prizeName(),
                draw.discountAmount(),
                draw.productId(),
                draw.skuId(),
                draw.imageUrl(),
                draw.payableAmount(),
                dao.findGifts(draw.id()),
                draw.status()
        );
    }

    private AdminDraw toAdminDraw(DrawRow draw) {
        return new AdminDraw(
                draw.id(),
                draw.campaignId(),
                draw.tierId(),
                draw.prizeId(),
                draw.orderId(),
                draw.orderNo(),
                draw.userId(),
                draw.productAmount(),
                draw.prizeIndex(),
                draw.prizeType(),
                draw.prizeName(),
                draw.discountAmount(),
                draw.productId(),
                draw.skuId(),
                draw.imageUrl(),
                draw.payableBefore(),
                draw.payableAmount(),
                fulfillmentStatus(draw),
                draw.status(),
                draw.giftStockStatus(),
                draw.voidReason(),
                format(draw.createdAt()),
                format(draw.createdAt()),
                format(draw.fulfilledAt()),
                draw.fulfillmentRemark(),
                dao.findGifts(draw.id())
        );
    }

    private String fulfillmentStatus(DrawRow draw) {
        if ("VOIDED".equals(draw.status())) {
            return "VOIDED";
        }
        if (!"GOODS".equals(draw.prizeType())) {
            return "NOT_REQUIRED";
        }
        return switch (draw.giftStockStatus()) {
            case "FULFILLED" -> "FULFILLED";
            case "RELEASED" -> "RELEASED";
            default -> "PENDING";
        };
    }

    private Campaign normalizeCampaign(Campaign request) {
        if (request == null) {
            throw new BusinessException(400, "活动配置不能为空");
        }
        List<Tier> tiers = new ArrayList<>();
        int nextTierSort = 10;
        for (Tier tier : request.tiers()) {
            if (tier == null) {
                throw new BusinessException(400, "阶梯配置不能为空");
            }
            List<Prize> prizes = new ArrayList<>();
            int nextPrizeSort = 10;
            for (Prize prize : tier.prizes()) {
                if (prize == null) {
                    throw new BusinessException(400, "奖项配置不能为空");
                }
                PrizeType type = LotteryRules.prizeType(prize.type());
                prizes.add(new Prize(
                        prize.id(),
                        type.name(),
                        trim(prize.name()),
                        type == PrizeType.DISCOUNT ? prize.discountAmount() : null,
                        type == PrizeType.GOODS ? prize.productId() : null,
                        type == PrizeType.GOODS ? prize.skuId() : null,
                        trimToNull(prize.imageUrl()),
                        prize.weight(),
                        type == PrizeType.GOODS ? prize.stockTotal() : 0,
                        prize.stockRemaining(),
                        prize.enabled() == null || prize.enabled(),
                        value(prize.sortOrder(), nextPrizeSort)
                ));
                nextPrizeSort += 10;
            }
            tiers.add(new Tier(
                    tier.id(),
                    trim(tier.name()),
                    tier.minProductAmount(),
                    tier.maxProductAmount(),
                    tier.enabled() == null || tier.enabled(),
                    value(tier.sortOrder(), nextTierSort),
                    prizes
            ));
            nextTierSort += 10;
        }
        return new Campaign(
                request.id(),
                Boolean.TRUE.equals(request.enabled()),
                trim(request.name()),
                format(LotteryRules.parseTime(request.startAt(), "活动开始时间")),
                format(LotteryRules.parseTime(request.endAt(), "活动结束时间")),
                value(request.dailyUserLimit(), 1),
                request.dailyBudgetAmount(),
                trimToNull(request.shareTitle()),
                trimToNull(request.shareDescription()),
                trimToNull(request.shareImageUrl()),
                tiers
        );
    }

    private PublicCampaign toPublic(Campaign campaign) {
        return new PublicCampaign(
                campaign.id(),
                campaign.enabled(),
                campaign.name(),
                campaign.startAt(),
                campaign.endAt(),
                campaign.dailyUserLimit(),
                campaign.shareTitle(),
                campaign.shareDescription(),
                campaign.shareImageUrl(),
                campaign.tiers().stream()
                        .filter(tier -> !Boolean.FALSE.equals(tier.enabled()))
                        .map(tier -> new PublicTier(
                                tier.id(),
                                tier.name(),
                                tier.minProductAmount(),
                                tier.maxProductAmount(),
                                tier.enabled(),
                                tier.sortOrder(),
                                tier.prizes().stream()
                                        .filter(prize -> !Boolean.FALSE.equals(prize.enabled()))
                                        .map(this::toPublic)
                                        .toList()
                        ))
                        .toList(),
                campaign.tiers().stream()
                        .filter(tier -> !Boolean.FALSE.equals(tier.enabled()))
                        .flatMap(tier -> tier.prizes().stream())
                        .filter(prize -> !Boolean.FALSE.equals(prize.enabled()))
                        .map(this::toPublic)
                        .toList()
        );
    }

    private PublicPrize toPublic(Prize prize) {
        return new PublicPrize(
                prize.id(),
                prize.type(),
                prize.name(),
                prize.discountAmount(),
                prize.productId(),
                prize.skuId(),
                prize.imageUrl(),
                prize.enabled(),
                prize.sortOrder()
        );
    }

    private List<PublicPrize> publicPrizes(Campaign campaign, int productAmount) {
        Tier tier = LotteryRules.matchingTier(campaign, productAmount);
        if (tier == null) {
            return List.of();
        }
        return tier.prizes().stream()
                .filter(prize -> !Boolean.FALSE.equals(prize.enabled()))
                .map(this::toPublic)
                .toList();
    }

    private Campaign requiredCampaign() {
        return dao.findCampaign()
                .orElseThrow(() -> new BusinessException(409, "抽奖活动尚未配置"));
    }

    private void ensureCampaignActive(Campaign campaign, LocalDateTime now) {
        String reason = campaignUnavailableReason(campaign, now);
        if (reason != null) {
            throw new BusinessException(409, reason);
        }
    }

    private String campaignUnavailableReason(Campaign campaign, LocalDateTime now) {
        if (campaign.id() == null || !Boolean.TRUE.equals(campaign.enabled())) {
            return "抽奖活动未开启";
        }
        LocalDateTime startAt = LotteryRules.parseTime(campaign.startAt(), "活动开始时间");
        LocalDateTime endAt = LotteryRules.parseTime(campaign.endAt(), "活动结束时间");
        if (startAt != null && now.isBefore(startAt)) {
            return "抽奖活动尚未开始";
        }
        if (endAt != null && !now.isBefore(endAt)) {
            return "抽奖活动已结束";
        }
        return null;
    }

    private Campaign emptyCampaign() {
        return new Campaign(null, false, "随机减免", null, null, 1, null, null, null, null, List.of());
    }

    private String challengeToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), STORE_ZONE);
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(ISO_TIME);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        String normalized = trim(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
