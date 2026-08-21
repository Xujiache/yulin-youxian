package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.common.PageResult;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.lottery.LotteryDao.ChallengeRow;
import com.xianda.freshdelivery.lottery.LotteryDao.DecisionRow;
import com.xianda.freshdelivery.lottery.LotteryDao.DrawGuardKey;
import com.xianda.freshdelivery.lottery.LotteryDao.DrawInsert;
import com.xianda.freshdelivery.lottery.LotteryDao.DrawRow;
import com.xianda.freshdelivery.lottery.LotteryModels.AdminDraw;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderPromotionProjection;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderState;
import com.xianda.freshdelivery.lottery.LotteryModels.DiscountMode;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeCode;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeType;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicCampaign;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicPrize;
import com.xianda.freshdelivery.lottery.LotteryModels.PublicTier;
import com.xianda.freshdelivery.lottery.LotteryModels.ReasonCode;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.StorefrontService.LotteryOrderView;
import com.xianda.freshdelivery.service.WechatPayClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LotteryService implements LotteryOrderLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(LotteryService.class);
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int MAX_FULFILLMENT_REMARK_LENGTH = 512;
    /** 已付款且尚未走到终点的订单才允许后台确认赠品履约。 */
    private static final List<String> FULFILLABLE_ORDER_STATUSES =
            List.of("已支付/待接单", "备货中", "配送中", "已完成", "部分退款");

    private final LotteryDao dao;
    private final StorefrontService storefrontService;
    private final WechatPayClient wechatPayClient;
    private final LotteryUnitOfWork unitOfWork;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Duration challengeTtl;
    private final Duration reconcileWindow;
    private final int reconcileBatchSize;
    private final Duration challengeRetention;
    private final Duration publicCacheTtl;

    private volatile CachedPublicCampaign publicCampaignCache;

    @Autowired
    public LotteryService(
            LotteryDao dao,
            StorefrontService storefrontService,
            WechatPayClient wechatPayClient,
            LotteryUnitOfWork unitOfWork,
            @Value("${marketing.lottery.challenge-ttl-seconds:600}") long challengeTtlSeconds,
            @Value("${marketing.lottery.reconcile-window-hours:72}") long reconcileWindowHours,
            @Value("${marketing.lottery.reconcile-batch-size:200}") int reconcileBatchSize,
            @Value("${marketing.lottery.challenge-retention-days:7}") long challengeRetentionDays,
            @Value("${marketing.lottery.public-cache-seconds:30}") long publicCacheSeconds
    ) {
        this(
                dao,
                storefrontService,
                wechatPayClient,
                unitOfWork,
                Clock.system(STORE_ZONE),
                new SecureRandom(),
                Duration.ofSeconds(Math.max(challengeTtlSeconds, 30)),
                Duration.ofHours(Math.max(reconcileWindowHours, 1)),
                reconcileBatchSize,
                Duration.ofDays(Math.max(challengeRetentionDays, 1)),
                Duration.ofSeconds(Math.max(publicCacheSeconds, 0))
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
        this(
                dao,
                storefrontService,
                wechatPayClient,
                LotteryUnitOfWork.direct(),
                clock,
                secureRandom,
                challengeTtl,
                Duration.ofHours(72),
                200,
                Duration.ofDays(7),
                Duration.ofSeconds(30)
        );
    }

    public LotteryService(
            LotteryDao dao,
            StorefrontService storefrontService,
            WechatPayClient wechatPayClient,
            LotteryUnitOfWork unitOfWork,
            Clock clock,
            SecureRandom secureRandom,
            Duration challengeTtl,
            Duration reconcileWindow,
            int reconcileBatchSize,
            Duration challengeRetention,
            Duration publicCacheTtl
    ) {
        this.dao = dao;
        this.storefrontService = storefrontService;
        this.wechatPayClient = wechatPayClient;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.challengeTtl = challengeTtl;
        this.reconcileWindow = reconcileWindow;
        this.reconcileBatchSize = Math.max(reconcileBatchSize, 1);
        this.challengeRetention = challengeRetention;
        this.publicCacheTtl = publicCacheTtl;
    }

    public Campaign adminCampaign() {
        return dao.findCampaign().orElseGet(this::emptyCampaign);
    }

    /**
     * 活动页是公开接口，不经登录拦截器，每次请求要展开活动 + 阶梯 + 奖项。加一层短 TTL
     * 缓存，避免被刷时把 10 个连接的池子占满。
     */
    public PublicCampaign publicCampaign() {
        LocalDateTime now = now();
        CachedPublicCampaign cached = publicCampaignCache;
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.campaign();
        }
        PublicCampaign campaign = toPublic(adminCampaign());
        publicCampaignCache = new CachedPublicCampaign(campaign, now.plus(publicCacheTtl));
        return campaign;
    }

    @Transactional
    public Campaign saveCampaign(Campaign request) {
        Campaign normalized = normalizeCampaign(request);
        LotteryRules.validate(normalized);
        Campaign saved = dao.saveCampaign(normalized);
        publicCampaignCache = null;
        return saved;
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

    /**
     * 抽奖分成两段：事务外做只读校验并关掉旧的微信 prepay（同步 HTTPS，最长 20 秒），
     * 事务内才去抢护栏行、扣库存、改价。关单必须发生在轮换 out_trade_no 之前，否则用户
     * 手机上那笔按旧价创建的支付回调回来会找不到订单。
     */
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
        ensureCampaignActive(campaign, now);
        if (challenge.campaignId() != campaign.id()) {
            throw new BusinessException(409, "活动配置已变化，请重新获取分享挑战");
        }
        if (mayChangePrice(campaign)) {
            closeActivePayment(order);
        }

        return unitOfWork.commitReadCommitted(() -> drawInTransaction(orderId, userId, order, challenge, now));
    }

    private DrawResult drawInTransaction(
            long orderId,
            long userId,
            OrderDetailDto order,
            ChallengeRow challenge,
            LocalDateTime now
    ) {
        DrawRow concurrentDraw = dao.findDrawByOrder(orderId).orElse(null);
        if (concurrentDraw != null) {
            return toResult(concurrentDraw);
        }
        Campaign campaign = requiredCampaign();
        ensureCampaignActive(campaign, now);
        if (challenge.campaignId() != campaign.id()) {
            throw new BusinessException(409, "活动配置已变化，请重新获取分享挑战");
        }
        Tier globalTier = requiredGlobalTier(campaign);

        LocalDate statDate = now.toLocalDate();
        // 加锁顺序固定为「用户行 -> 预算行」，两把锁都是行级，避免死锁也避免锁住整个活动。
        dao.lockDrawGuard(new DrawGuardKey(campaign.id(), statDate, userId));
        ensureDailyLimit(campaign, userId, now);
        if (campaign.dailyBudgetAmount() != null) {
            dao.lockDrawGuard(new DrawGuardKey(campaign.id(), statDate, LotteryDao.BUDGET_GUARD_USER));
        }
        int spent = dailyDiscountSpent(campaign, now);
        List<Prize> displayPrizes = displayPrizes(campaign);
        List<Prize> eligible = eligiblePrizes(displayPrizes, order.payableAmount(), spent, campaign.dailyBudgetAmount());
        if (eligible.isEmpty()) {
            throw new BusinessException(409, "当前没有可抽取的奖项");
        }

        Prize selected = LotteryRules.selectByProbability(eligible, secureRandom);
        PrizeCode prizeCode = LotteryRules.prizeCode(selected.prizeCode());
        PrizeType type = prizeCode == PrizeCode.NONE ? PrizeType.NONE : PrizeType.DISCOUNT;
        int discountAmount = LotteryRules.actualDiscount(selected, order.payableAmount()).orElse(0);
        int payableAmount = Math.max(1, order.payableAmount() - discountAmount);
        int prizeIndex = prizeCode.slotIndex();
        DrawInsert insert = new DrawInsert(
                campaign.id(),
                globalTier.id(),
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
                null,
                prizeCode.name()
        );
        long drawId = 0;
        try {
            drawId = dao.insertDraw(insert, now);
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
                    List.of(),
                    "APPLIED",
                    prizeCode.name()
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
            throw exception;
        }
    }

    public PageResult<AdminDraw> draws(
            String keyword,
            String prizeType,
            String status,
            String startAt,
            String endAt,
            Integer page,
            Integer size
    ) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        int currentPage = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, 200);
        // 时间窗必须和 status 一样下推到 SQL，否则翻页时会按整表分页再筛，页与页之间漏记录。
        LocalDateTime windowStart = parseFilterTime(startAt, false);
        LocalDateTime windowEnd = parseFilterTime(endAt, true);
        long total = dao.countDraws(keyword, prizeType, normalizedStatus, windowStart, windowEnd);
        List<DrawRow> rows = dao.searchDraws(
                keyword,
                prizeType,
                normalizedStatus,
                windowStart,
                windowEnd,
                (currentPage - 1) * pageSize,
                pageSize
        );
        return new PageResult<>(toAdminDraws(rows), total, currentPage, pageSize);
    }

    /**
     * 后台的时间选择器发的是 yyyy-MM-ddTHH:mm:ss；这里同时容忍空格分隔和只有日期的写法，
     * 只有日期时按当天 00:00:00 / 23:59:59 补齐，格式无法识别就当作没有筛选。
     */
    private LocalDateTime parseFilterTime(String value, boolean endOfDay) {
        String text = trim(value);
        if (text.isEmpty()) {
            return null;
        }
        String normalized = text.replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized);
        } catch (RuntimeException notDateTime) {
            try {
                LocalDate date = LocalDate.parse(normalized);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            } catch (RuntimeException notDate) {
                LOGGER.warn("忽略无法识别的抽奖流水时间筛选值: {}", text);
                return null;
            }
        }
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
        String normalizedRemark = trimToNull(remark);
        if (normalizedRemark != null && normalizedRemark.length() > MAX_FULFILLMENT_REMARK_LENGTH) {
            throw new BusinessException(400, "履约备注不能超过 " + MAX_FULFILLMENT_REMARK_LENGTH + " 个字符");
        }
        ensureOrderFulfillable(draw);
        if (!"FULFILLED".equals(draw.giftStockStatus())) {
            storefrontService.fulfillLotteryPromotion(draw.orderId());
            dao.fulfillDraw(draw.id(), normalizedRemark, now());
        }
        dao.settleDraw(draw.id(), now());
        return toAdminDraws(List.of(dao.findDraw(drawId).orElseThrow())).get(0);
    }

    /**
     * 待支付订单的赠品一旦被标成 FULFILLED，用户再取消订单时营销库存和真实 SKU 库存都
     * 不会回补，两套库存永久少一件，对账也修不回来。
     */
    private void ensureOrderFulfillable(DrawRow draw) {
        LotteryOrderView order = storefrontService.lotteryOrderView(draw.orderId());
        if (order == null) {
            throw new BusinessException(409, "订单不存在，无法确认赠品履约");
        }
        if (order.paidAmount() <= 0) {
            throw new BusinessException(409, "订单尚未支付，赠品不能确认履约");
        }
        if (!FULFILLABLE_ORDER_STATUSES.contains(order.effectiveStatus())) {
            throw new BusinessException(
                    409,
                    "订单当前状态为「" + order.effectiveStatus() + "」，赠品不能确认履约"
            );
        }
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
    public void onOrderPaid(long orderId, LocalDateTime paidAt) {
        dao.markDrawPaid(orderId, paidAt == null ? now() : paidAt);
    }

    /**
     * 赠品在订单超时关闭时已经释放，重启支付时不一定还拿得回来（运营停用了该奖项、库存
     * 被抽光、奖项本身被删掉）。这时候不能把整张订单卡死，降级成作废赠品、恢复原价、
     * 允许继续支付，作废原因留在流水上可追溯。
     */
    @Override
    @Transactional
    public void onOrderRestarting(long orderId) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || !"APPLIED".equals(draw.status())
                || !"RELEASED".equals(draw.giftStockStatus())) {
            return;
        }
        if (dao.reserveMarketingStock(draw.prizeId()) != 1) {
            abandonGift(draw, "RESTART_MARKETING_STOCK_UNAVAILABLE");
            return;
        }
        try {
            storefrontService.reserveLotteryPromotion(orderId);
            dao.reserveReleasedGift(draw.id(), now());
        } catch (BusinessException exception) {
            dao.restoreMarketingStock(draw.prizeId());
            LOGGER.warn(
                    "重启支付时赠品库存不足，改为作废赠品并按原价支付，orderId={}, drawId={}",
                    orderId,
                    draw.id(),
                    exception
            );
            abandonGift(draw, "RESTART_GIFT_STOCK_UNAVAILABLE");
        } catch (RuntimeException exception) {
            dao.restoreMarketingStock(draw.prizeId());
            throw exception;
        }
    }

    private void abandonGift(DrawRow draw, String reason) {
        storefrontService.rollbackLotteryPromotion(draw.orderId(), draw.id(), draw.payableBefore());
        dao.voidDraw(draw.id(), reason, now());
        LOGGER.warn("赠品已作废并恢复原价，orderId={}, drawId={}, reason={}", draw.orderId(), draw.id(), reason);
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
        if (draw == null || !"APPLIED".equals(draw.status())) {
            return;
        }
        if ("RESERVED".equals(draw.giftStockStatus())) {
            storefrontService.fulfillLotteryPromotion(orderId);
            dao.fulfillDraw(draw.id(), "随订单履约完成", now());
        }
        dao.settleDraw(draw.id(), now());
    }

    /**
     * @param giftAlreadyDispatched 赠品是否已经随订单发出（订单已进入配送中或之后）。
     *                              已发出的赠品不回补库存，按营销损失处理，与订单商品同口径。
     */
    @Override
    @Transactional
    public void onOrderFullyRefunded(long orderId, boolean giftAlreadyDispatched) {
        DrawRow draw = dao.findDrawByOrder(orderId).orElse(null);
        if (draw == null || "VOIDED".equals(draw.status())) {
            return;
        }
        if (giftAlreadyDispatched) {
            if ("RESERVED".equals(draw.giftStockStatus())) {
                storefrontService.fulfillLotteryPromotion(orderId);
                dao.fulfillDraw(draw.id(), "订单已发出，退款不回补赠品", now());
                LOGGER.warn(
                        "全额退款时赠品已随单发出，按营销损失处理不回补库存，orderId={}, drawId={}",
                        orderId,
                        draw.id()
                );
            }
            dao.settleDraw(draw.id(), now());
            return;
        }
        if ("FULFILLED".equals(draw.giftStockStatus())) {
            return;
        }
        releaseGiftStockIfNeeded(draw);
        storefrontService.releaseLotteryPromotion(orderId, true);
        dao.voidDraw(draw.id(), "FULL_REFUND_BEFORE_FULFILLMENT", now());
    }

    /**
     * 对账只处理时间窗内的在途流水，每条一个短事务；终态流水不再进入扫描范围。
     */
    @Scheduled(
            fixedDelayString = "${marketing.lottery.consistency-scan-ms:300000}",
            initialDelayString = "${marketing.lottery.consistency-initial-delay-ms:30000}"
    )
    public void reconcile() {
        LocalDateTime now = now();
        LocalDateTime windowStart = now.minus(reconcileWindow);
        storefrontService.closeExpiredOrders();
        reconcileOrphanProjections();
        for (DrawRow draw : dao.findDrawsForReconcile("RESERVED", windowStart, reconcileBatchSize)) {
            unitOfWork.run(() -> reconcileReservedDraw(draw, now));
        }
        for (DrawRow draw : dao.findDrawsForReconcile("APPLIED", windowStart, reconcileBatchSize)) {
            unitOfWork.run(() -> reconcileAppliedDraw(draw, now));
        }
    }

    /**
     * 快照里还留着投影、数据库里的流水已经没了。恢复原价之前必须先关掉微信那边按折后价
     * 创建的 prepay，否则轮换 out_trade_no 之后用户付的钱回调会找不到订单。
     */
    private void reconcileOrphanProjections() {
        List<Map.Entry<Long, OrderPromotionProjection>> orphans = storefrontService.lotteryPromotions()
                .entrySet().stream()
                .filter(entry -> entry.getValue().drawId() != null)
                .filter(entry -> dao.findDraw(entry.getValue().drawId()).isEmpty())
                .limit(reconcileBatchSize)
                .toList();
        for (Map.Entry<Long, OrderPromotionProjection> entry : orphans) {
            long orderId = entry.getKey();
            OrderPromotionProjection projection = entry.getValue();
            int payableBefore = value(projection.payableAmount(), 1) + value(projection.discountAmount(), 0);
            try {
                closeActivePaymentBeforeRepricing(orderId, payableBefore);
            } catch (RuntimeException exception) {
                LOGGER.error("回滚抽奖投影前关闭微信支付单失败，本轮跳过，orderId={}", orderId, exception);
                continue;
            }
            unitOfWork.run(() -> storefrontService.rollbackLotteryPromotion(
                    orderId,
                    projection.drawId(),
                    payableBefore
            ));
        }
    }

    private void reconcileReservedDraw(DrawRow draw, LocalDateTime now) {
        OrderPromotionProjection projection = storefrontService.lotteryPromotion(draw.orderId());
        if (projection != null && Objects.equals(projection.drawId(), draw.id())) {
            dao.markDrawApplied(draw.id(), now);
            dao.saveDecision(draw.orderId(), draw.userId(), draw.campaignId(), "DRAWN", draw.id(), now);
            return;
        }
        if ("RESERVED".equals(draw.giftStockStatus())) {
            dao.restoreMarketingStock(draw.prizeId());
            dao.releaseGift(draw.id(), now);
        }
        dao.voidDraw(draw.id(), "RESERVED_WITHOUT_STOREFRONT_PROJECTION", now);
    }

    private void reconcileAppliedDraw(DrawRow draw, LocalDateTime now) {
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
                return;
            }
            projection = storefrontService.lotteryPromotion(draw.orderId());
        }
        if (projection != null && "VOIDED".equals(projection.status())) {
            if ("RESERVED".equals(draw.giftStockStatus())) {
                dao.restoreMarketingStock(draw.prizeId());
                dao.releaseGift(draw.id(), now);
            }
            dao.voidDraw(draw.id(), "STOREFRONT_PROJECTION_VOIDED", now);
            return;
        }
        LotteryOrderView order = storefrontService.lotteryOrderView(draw.orderId());
        if (order == null) {
            return;
        }
        if ("RELEASED".equals(draw.giftStockStatus())
                && projection != null
                && projection.gifts().stream().anyMatch(gift -> "RESERVED".equals(gift.status()))) {
            storefrontService.releaseLotteryPromotion(draw.orderId(), false);
        } else if ("FULFILLED".equals(draw.giftStockStatus())
                && projection != null
                && projection.gifts().stream().anyMatch(gift -> !"FULFILLED".equals(gift.status()))) {
            storefrontService.fulfillLotteryPromotion(draw.orderId());
        }
        switch (order.status()) {
            case "已取消" -> onOrderCancelled(draw.orderId(), "CONSISTENCY_CANCELLED");
            case "已关闭" -> onOrderExpired(draw.orderId());
            case "已完成" -> onOrderFulfilled(draw.orderId());
            case "已退款" -> onOrderFullyRefunded(draw.orderId(), order.dispatched());
            default -> {
                // 在途订单继续留在对账范围内。
            }
        }
    }

    /**
     * challenge 表每张订单每个 TTL 窗口至少一行，只写不删会无限膨胀。
     */
    @Scheduled(
            fixedDelayString = "${marketing.lottery.challenge-cleanup-ms:3600000}",
            initialDelayString = "${marketing.lottery.challenge-cleanup-initial-delay-ms:120000}"
    )
    public void cleanupChallenges() {
        LocalDateTime cutoff = now().minus(challengeRetention);
        int deleted = dao.deleteStaleChallenges(cutoff, Math.max(reconcileBatchSize, 1) * 10);
        if (deleted > 0) {
            LOGGER.info("清理过期抽奖挑战 {} 条，cutoff={}", deleted, cutoff);
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
            List<PublicPrize> prizes = publicPrizes(campaign);
            return new OrderState(
                    false,
                    "该订单已抽奖",
                    ReasonCode.ALREADY_DRAWN,
                    null,
                    true,
                    true,
                    publicCampaign,
                    prizes,
                    alignPrizeIndex(toResult(draw), prizes)
            );
        }
        DecisionRow decision = dao.findDecision(orderId).orElse(null);
        if (decision != null && "SKIPPED".equals(decision.decision())) {
            return unavailable(
                    publicCampaign, campaign, order, now,
                    "订单已跳过抽奖并锁定价格", ReasonCode.ORDER_SKIPPED, challenge
            );
        }
        if (!"待支付".equals(order.status())) {
            return unavailable(
                    publicCampaign, campaign, order, now,
                    "仅待支付订单可以抽奖", ReasonCode.ORDER_NOT_PENDING, challenge
            );
        }
        CampaignBlock campaignBlock = campaignUnavailableReason(campaign, now);
        if (campaignBlock != null) {
            return unavailable(
                    publicCampaign, campaign, order, now,
                    campaignBlock.reason(), campaignBlock.code(), challenge
            );
        }
        if (storefrontService.hasActivePaymentShare(orderId)) {
            return unavailable(
                    publicCampaign, campaign, order, now,
                    "已有有效好友代付链接，不能再抽奖", ReasonCode.ACTIVE_PAYMENT_SHARE, challenge
            );
        }
        if (dao.countUserDraws(
                campaign.id(),
                userId,
                startOfDay(now),
                startOfDay(now).plusDays(1)
        ) >= campaign.dailyUserLimit()) {
            return unavailable(
                    publicCampaign, campaign, order, now,
                    "今日抽奖次数已用完", ReasonCode.DAILY_LIMIT_REACHED, challenge
            );
        }
        List<Prize> eligible = eligiblePrizes(
                displayPrizes(campaign),
                order.payableAmount(),
                dailyDiscountSpent(campaign, now),
                campaign.dailyBudgetAmount()
        );
        if (eligible.isEmpty()) {
            return unavailable(
                    publicCampaign, campaign, order, now,
                    "当前没有可抽取的奖项", ReasonCode.NO_AVAILABLE_PRIZE, challenge
            );
        }
        if (challenge != null && isChallengeStale(challenge, now)) {
            return unavailable(
                    publicCampaign, campaign, order, now,
                    "分享挑战已过期，请重新获取", ReasonCode.CHALLENGE_EXPIRED, null
            );
        }
        return new OrderState(
                true,
                "",
                ReasonCode.ELIGIBLE,
                challenge == null ? null : challenge.token(),
                challenge != null && challenge.shareTriggeredAt() != null,
                false,
                publicCampaign,
                publicPrizes(campaign),
                null
        );
    }

    private OrderState unavailable(
            PublicCampaign publicCampaign,
            Campaign campaign,
            OrderDetailDto order,
            LocalDateTime now,
            String reason,
            ReasonCode reasonCode,
            ChallengeRow challenge
    ) {
        return new OrderState(
                false,
                reason,
                reasonCode,
                challenge == null ? null : challenge.token(),
                challenge != null && challenge.shareTriggeredAt() != null,
                false,
                publicCampaign,
                publicPrizes(campaign),
                null
        );
    }

    private boolean isChallengeStale(ChallengeRow challenge, LocalDateTime now) {
        return challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now);
    }

    private List<Prize> displayPrizes(Campaign campaign) {
        return LotteryRules.sortedFixedPrizes(campaign.prizes());
    }

    private List<Prize> eligiblePrizes(
            List<Prize> prizes,
            int payableBefore,
            int spent,
            Integer dailyBudgetAmount
    ) {
        long budget = dailyBudgetAmount == null ? Long.MAX_VALUE : dailyBudgetAmount;
        List<Prize> eligible = new ArrayList<>();
        for (Prize prize : LotteryRules.sortedFixedPrizes(prizes)) {
            int probability = prize.probabilityBp() == null ? 0 : prize.probabilityBp();
            if (probability <= 0) {
                continue;
            }
            OptionalInt discount = LotteryRules.actualDiscount(prize, payableBefore);
            if (discount.isEmpty()) {
                continue;
            }
            PrizeCode code = LotteryRules.prizeCodeOrNull(prize.prizeCode());
            if (code != PrizeCode.NONE && (long) spent + discount.getAsInt() > budget) {
                continue;
            }
            eligible.add(prize);
        }
        return eligible;
    }

    /**
     * 新流水的 prizeIndex 是固定槽位 0–3。回读时优先按 prizeId，再按 prizeCode；
     * 历史 GOODS 流水不在四格里时保留原下标，避免改写历史结果。
     */
    private DrawResult alignPrizeIndex(DrawResult result, List<PublicPrize> prizes) {
        int index = indexOfPrize(prizes, result.prizeId(), result.prizeCode());
        if (index < 0 || Objects.equals(result.prizeIndex(), index)) {
            return result;
        }
        return new DrawResult(
                result.drawId(),
                result.prizeId(),
                index,
                result.prizeType(),
                result.prizeName(),
                result.discountAmount(),
                result.productId(),
                result.skuId(),
                result.imageUrl(),
                result.payableAmount(),
                result.gifts(),
                result.status(),
                result.prizeCode()
        );
    }

    private int indexOfPrize(List<PublicPrize> prizes, Long prizeId, String prizeCode) {
        if (prizeId != null) {
            for (int position = 0; position < prizes.size(); position++) {
                if (Objects.equals(prizes.get(position).id(), prizeId)) {
                    return position;
                }
            }
        }
        PrizeCode code = LotteryRules.prizeCodeOrNull(prizeCode);
        if (code != null) {
            for (int position = 0; position < prizes.size(); position++) {
                if (code.name().equals(prizes.get(position).prizeCode())) {
                    return position;
                }
            }
        }
        return -1;
    }

    private boolean mayChangePrice(Campaign campaign) {
        return displayPrizes(campaign).stream()
                .anyMatch(prize -> {
                    PrizeCode code = LotteryRules.prizeCodeOrNull(prize.prizeCode());
                    return code != null
                            && code != PrizeCode.NONE
                            && prize.probabilityBp() != null
                            && prize.probabilityBp() > 0;
                });
    }

    private void closeActivePayment(OrderDetailDto order) {
        if (wechatPayClient == null || !wechatPayClient.isPaymentConfigured()) {
            return;
        }
        wechatPayClient.closePayment(order);
    }

    private void closeActivePaymentBeforeRepricing(long orderId, int payableBefore) {
        if (wechatPayClient == null || !wechatPayClient.isPaymentConfigured()) {
            return;
        }
        OrderDetailDto order;
        try {
            order = storefrontService.adminOrder(orderId);
        } catch (BusinessException missingOrder) {
            return;
        }
        if (!"待支付".equals(order.status()) || Objects.equals(order.payableAmount(), payableBefore)) {
            return;
        }
        wechatPayClient.closePayment(order);
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
                draw.status(),
                draw.prizeCode()
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
                draw.status(),
                draw.prizeCode()
        );
    }

    private List<AdminDraw> toAdminDraws(List<DrawRow> rows) {
        List<Long> drawIds = rows.stream().map(DrawRow::id).toList();
        List<Long> orderIds = rows.stream().map(DrawRow::orderId).distinct().toList();
        Map<Long, List<Gift>> gifts = dao.findGiftsByDraws(drawIds);
        Map<Long, LocalDateTime> shareTriggeredAt = dao.findShareTriggeredAt(orderIds);
        return rows.stream()
                .map(draw -> toAdminDraw(draw, gifts, shareTriggeredAt))
                .toList();
    }

    private AdminDraw toAdminDraw(
            DrawRow draw,
            Map<Long, List<Gift>> gifts,
            Map<Long, LocalDateTime> shareTriggeredAt
    ) {
        LotteryOrderView order = storefrontService.lotteryOrderView(draw.orderId());
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
                order == null ? null : order.status(),
                format(shareTriggeredAt.get(draw.orderId())),
                format(draw.appliedAt() == null ? draw.createdAt() : draw.appliedAt()),
                format(draw.createdAt()),
                format(draw.paidAt()),
                format(draw.fulfilledAt()),
                draw.fulfillmentRemark(),
                gifts.getOrDefault(draw.id(), List.of()),
                draw.prizeCode()
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
        List<Prize> prizes = new ArrayList<>();
        for (PrizeCode code : PrizeCode.values()) {
            Prize source = request.prizes().stream()
                    .filter(prize -> prize != null && code == LotteryRules.prizeCodeOrNull(prize.prizeCode()))
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                throw new BusinessException(400, "缺少固定奖项：" + code.displayName());
            }
            prizes.add(normalizeFixedPrize(code, source));
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
                List.of(),
                prizes
        );
    }

    private Prize normalizeFixedPrize(PrizeCode code, Prize source) {
        int probability = source.probabilityBp() == null ? 0 : source.probabilityBp();
        DiscountMode mode = code == PrizeCode.NONE
                ? DiscountMode.NONE
                : hasText(source.discountMode())
                        ? LotteryRules.discountMode(source.discountMode())
                        : DiscountMode.PERCENTAGE;
        if (code == PrizeCode.NONE || mode == DiscountMode.NONE) {
            return Prize.fixed(source.id(), code, probability, DiscountMode.NONE, null, null, null, null);
        }
        if (mode == DiscountMode.THRESHOLD) {
            return Prize.fixed(
                    source.id(),
                    code,
                    probability,
                    DiscountMode.THRESHOLD,
                    source.thresholdAmount(),
                    source.fixedDiscountAmount(),
                    null,
                    null
            );
        }
        return Prize.fixed(
                source.id(),
                code,
                probability,
                DiscountMode.PERCENTAGE,
                null,
                null,
                source.discountRateBp(),
                source.maxDiscountAmount()
        );
    }

    private PublicCampaign toPublic(Campaign campaign) {
        List<PublicPrize> prizes = publicPrizes(campaign);
        Tier global = campaign.tiers().stream()
                .filter(tier -> "GLOBAL".equals(tier.poolCode()))
                .findFirst()
                .orElse(null);
        List<PublicTier> tiers = global == null
                ? List.of()
                : List.of(new PublicTier(
                        global.id(),
                        "全部订单",
                        0,
                        null,
                        true,
                        10,
                        prizes
                ));
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
                tiers,
                prizes
        );
    }

    private PublicPrize toPublic(Prize prize) {
        Integer displayDiscount = null;
        if (LotteryRules.prizeCodeOrNull(prize.prizeCode()) == PrizeCode.NONE) {
            displayDiscount = 0;
        } else if (LotteryRules.discountMode(prize.discountMode()) == DiscountMode.THRESHOLD) {
            displayDiscount = prize.fixedDiscountAmount();
        }
        return new PublicPrize(
                prize.id(),
                prize.type(),
                prize.name(),
                displayDiscount,
                prize.productId(),
                prize.skuId(),
                prize.imageUrl(),
                prize.enabled(),
                prize.sortOrder(),
                prize.prizeCode()
        );
    }

    private List<PublicPrize> publicPrizes(Campaign campaign) {
        return displayPrizes(campaign).stream().map(this::toPublic).toList();
    }

    private Tier requiredGlobalTier(Campaign campaign) {
        return campaign.tiers().stream()
                .filter(tier -> "GLOBAL".equals(tier.poolCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(409, "抽奖活动尚未配置"));
    }

    private Campaign requiredCampaign() {
        return dao.findCampaign()
                .orElseThrow(() -> new BusinessException(409, "抽奖活动尚未配置"));
    }

    private void ensureCampaignActive(Campaign campaign, LocalDateTime now) {
        CampaignBlock block = campaignUnavailableReason(campaign, now);
        if (block != null) {
            throw new BusinessException(409, block.reason());
        }
    }

    private CampaignBlock campaignUnavailableReason(Campaign campaign, LocalDateTime now) {
        if (campaign.id() == null || !Boolean.TRUE.equals(campaign.enabled())) {
            return new CampaignBlock("抽奖活动未开启", ReasonCode.CAMPAIGN_DISABLED);
        }
        LocalDateTime startAt = LotteryRules.parseTime(campaign.startAt(), "活动开始时间");
        LocalDateTime endAt = LotteryRules.parseTime(campaign.endAt(), "活动结束时间");
        if (startAt != null && now.isBefore(startAt)) {
            return new CampaignBlock("抽奖活动尚未开始", ReasonCode.CAMPAIGN_NOT_STARTED);
        }
        if (endAt != null && !now.isBefore(endAt)) {
            return new CampaignBlock("抽奖活动已结束", ReasonCode.CAMPAIGN_ENDED);
        }
        return null;
    }

    private Campaign emptyCampaign() {
        return new Campaign(null, false, "随机减免", null, null, 1, null, null, null, null, List.of(), List.of());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private record CampaignBlock(String reason, ReasonCode code) {
    }

    private record CachedPublicCampaign(PublicCampaign campaign, LocalDateTime expiresAt) {
    }
}
