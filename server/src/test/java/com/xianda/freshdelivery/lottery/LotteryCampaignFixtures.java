package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.lottery.LotteryDao.DrawInsert;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.DiscountMode;
import com.xianda.freshdelivery.lottery.LotteryModels.DrawResult;
import com.xianda.freshdelivery.lottery.LotteryModels.Gift;
import com.xianda.freshdelivery.lottery.LotteryModels.OrderPromotionProjection;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeCode;
import com.xianda.freshdelivery.service.StorefrontService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

final class LotteryCampaignFixtures {
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");

    private LotteryCampaignFixtures() {
    }

    static Campaign campaign(Integer dailyLimit, Integer dailyBudget, List<Prize> prizes) {
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
                List.of(),
                prizes
        );
    }

    static List<Prize> onlyNone() {
        return List.of(
                inactive(PrizeCode.FIRST),
                inactive(PrizeCode.SECOND),
                inactive(PrizeCode.THIRD),
                thankYou(10_000)
        );
    }

    static List<Prize> firstThreshold(int probabilityBp, int thresholdAmount, int discountAmount) {
        return List.of(
                threshold(PrizeCode.FIRST, probabilityBp, thresholdAmount, discountAmount),
                inactive(PrizeCode.SECOND),
                inactive(PrizeCode.THIRD),
                thankYou(10_000 - probabilityBp)
        );
    }

    static List<Prize> firstPercentage(int probabilityBp, int rateBp, int maxDiscountAmount) {
        return List.of(
                percentage(PrizeCode.FIRST, probabilityBp, rateBp, maxDiscountAmount),
                inactive(PrizeCode.SECOND),
                inactive(PrizeCode.THIRD),
                thankYou(10_000 - probabilityBp)
        );
    }

    static Prize inactive(PrizeCode code) {
        return Prize.fixed(null, code, 0, DiscountMode.PERCENTAGE, null, null, null, null);
    }

    static Prize thankYou(int probabilityBp) {
        return Prize.fixed(null, PrizeCode.NONE, probabilityBp, DiscountMode.NONE, null, null, null, null);
    }

    static Prize threshold(PrizeCode code, int probabilityBp, int thresholdAmount, int discountAmount) {
        return Prize.fixed(
                null,
                code,
                probabilityBp,
                DiscountMode.THRESHOLD,
                thresholdAmount,
                discountAmount,
                null,
                null
        );
    }

    static Prize percentage(PrizeCode code, int probabilityBp, int rateBp, int maxDiscountAmount) {
        return Prize.fixed(
                null,
                code,
                probabilityBp,
                DiscountMode.PERCENTAGE,
                null,
                null,
                rateBp,
                maxDiscountAmount
        );
    }

    static DrawResult seedHistoricalGoodsDraw(
            LotteryDao dao,
            StorefrontService storefront,
            JdbcTemplate jdbcTemplate,
            OrderDetailDto order,
            long userId,
            long productId,
            int stock
    ) {
        Long campaignId = jdbcTemplate.queryForObject(
                "SELECT id FROM marketing_lottery_campaign ORDER BY id LIMIT 1",
                Long.class
        );
        Long prizeId = jdbcTemplate.query("""
                SELECT id FROM marketing_lottery_prize
                WHERE type = 'GOODS' AND name = '历史赠品'
                ORDER BY id
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null);
        Long tierId;
        if (prizeId == null) {
            jdbcTemplate.update("""
                    INSERT INTO marketing_lottery_tier (
                        campaign_id, name, min_product_amount, max_product_amount, enabled, sort_order
                    ) VALUES (?, '历史赠品阶梯', 0, NULL, 0, 90)
                    """, campaignId);
            tierId = jdbcTemplate.queryForObject(
                    "SELECT id FROM marketing_lottery_tier WHERE name = '历史赠品阶梯' ORDER BY id DESC LIMIT 1",
                    Long.class
            );
            jdbcTemplate.update("""
                    INSERT INTO marketing_lottery_prize (
                        campaign_id, tier_id, type, name, discount_amount, product_id, sku_id, image_url,
                        weight, stock_total, stock_remaining, enabled, sort_order
                    ) VALUES (?, ?, 'GOODS', '历史赠品', NULL, ?, NULL, NULL, 1, ?, ?, 1, 90)
                    """, campaignId, tierId, productId, stock, stock);
            prizeId = jdbcTemplate.queryForObject(
                    "SELECT id FROM marketing_lottery_prize WHERE name = '历史赠品' ORDER BY id DESC LIMIT 1",
                    Long.class
            );
        } else {
            tierId = jdbcTemplate.queryForObject(
                    "SELECT tier_id FROM marketing_lottery_prize WHERE id = ?",
                    Long.class,
                    prizeId
            );
            jdbcTemplate.update("""
                    UPDATE marketing_lottery_prize
                    SET enabled = 1, product_id = ?
                    WHERE id = ?
                    """, productId, prizeId);
        }
        Gift giftTemplate = storefront.lotteryGift(productId, null, null, prizeId, null);
        dao.reserveMarketingStock(prizeId);
        LocalDateTime now = LocalDateTime.now(STORE_ZONE);
        long drawId = dao.insertDraw(new DrawInsert(
                campaignId,
                tierId,
                prizeId,
                order.id(),
                order.orderNo(),
                userId,
                order.productAmount(),
                0,
                "GOODS",
                "历史赠品",
                0,
                productId,
                null,
                null,
                order.payableAmount(),
                order.payableAmount(),
                giftTemplate,
                null
        ), now);
        Gift gift = new Gift(
                drawId,
                prizeId,
                giftTemplate.productId(),
                giftTemplate.skuId(),
                giftTemplate.productName(),
                giftTemplate.skuName(),
                giftTemplate.imageUrl(),
                giftTemplate.quantity(),
                "RESERVED"
        );
        dao.insertGift(drawId, prizeId, order.id(), gift, now);
        storefront.applyLotteryPromotion(order.id(), userId, new OrderPromotionProjection(
                drawId,
                prizeId,
                0,
                "GOODS",
                "历史赠品",
                0,
                productId,
                gift.skuId(),
                gift.imageUrl(),
                order.payableAmount(),
                List.of(gift),
                "APPLIED",
                null
        ));
        dao.markDrawApplied(drawId, now);
        dao.saveDecision(order.id(), userId, campaignId, "DRAWN", drawId, now);
        return new DrawResult(
                drawId,
                prizeId,
                0,
                "GOODS",
                "历史赠品",
                0,
                productId,
                gift.skuId(),
                gift.imageUrl(),
                order.payableAmount(),
                List.of(gift),
                "APPLIED",
                null
        );
    }
}
