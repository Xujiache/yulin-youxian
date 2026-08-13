package com.xianda.freshdelivery.lottery;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

public final class LotteryModels {
    private LotteryModels() {
    }

    public enum PrizeType {
        DISCOUNT,
        GOODS,
        NONE
    }

    /**
     * {@link OrderState#reason()} 是给用户看的中文文案，会随运营口径调整；小程序按
     * reasonCode 做分支判断，两者不能混用。
     */
    public enum ReasonCode {
        ELIGIBLE,
        ALREADY_DRAWN,
        ORDER_NOT_PENDING,
        ORDER_SKIPPED,
        ACTIVE_PAYMENT_SHARE,
        DAILY_LIMIT_REACHED,
        TIER_NOT_MATCHED,
        CAMPAIGN_DISABLED,
        CAMPAIGN_NOT_STARTED,
        CAMPAIGN_ENDED,
        CHALLENGE_EXPIRED,
        NO_AVAILABLE_PRIZE
    }

    public record Campaign(
            Long id,
            Boolean enabled,
            String name,
            String startAt,
            String endAt,
            Integer dailyUserLimit,
            Integer dailyBudgetAmount,
            String shareTitle,
            String shareDescription,
            String shareImageUrl,
            List<Tier> tiers
    ) {
        public Campaign {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }

    public record Tier(
            Long id,
            String name,
            Integer minProductAmount,
            Integer maxProductAmount,
            Boolean enabled,
            Integer sortOrder,
            List<Prize> prizes
    ) {
        public Tier {
            prizes = prizes == null ? List.of() : List.copyOf(prizes);
        }
    }

    public record Prize(
            Long id,
            String type,
            String name,
            Integer discountAmount,
            Long productId,
            Long skuId,
            String imageUrl,
            Integer weight,
            Integer stockTotal,
            Integer stockRemaining,
            Boolean enabled,
            Integer sortOrder
    ) {
    }

    public record PublicCampaign(
            Long id,
            Boolean enabled,
            String name,
            String startAt,
            String endAt,
            Integer dailyUserLimit,
            String shareTitle,
            String shareDescription,
            String shareImageUrl,
            List<PublicTier> tiers,
            List<PublicPrize> prizes
    ) {
        public PublicCampaign {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
            prizes = prizes == null ? List.of() : List.copyOf(prizes);
        }
    }

    public record PublicTier(
            Long id,
            String name,
            Integer minProductAmount,
            Integer maxProductAmount,
            Boolean enabled,
            Integer sortOrder,
            List<PublicPrize> prizes
    ) {
        public PublicTier {
            prizes = prizes == null ? List.of() : List.copyOf(prizes);
        }
    }

    public record PublicPrize(
            Long id,
            String type,
            String name,
            Integer discountAmount,
            Long productId,
            Long skuId,
            String imageUrl,
            Boolean enabled,
            Integer sortOrder
    ) {
    }

    public record Gift(
            Long drawId,
            Long prizeId,
            Long productId,
            Long skuId,
            String productName,
            String skuName,
            String imageUrl,
            BigDecimal quantity,
            String status
    ) {
    }

    public record DrawResult(
            Long drawId,
            Long prizeId,
            Integer prizeIndex,
            String prizeType,
            String prizeName,
            Integer discountAmount,
            Long productId,
            Long skuId,
            String imageUrl,
            Integer payableAmount,
            List<Gift> gifts,
            String status
    ) {
        public DrawResult {
            gifts = gifts == null ? List.of() : List.copyOf(gifts);
        }
    }

    public record OrderState(
            Boolean eligible,
            String reason,
            ReasonCode reasonCode,
            String challengeToken,
            Boolean shareTriggered,
            Boolean drawn,
            PublicCampaign campaign,
            List<PublicPrize> prizes,
            DrawResult result
    ) {
        public OrderState {
            prizes = prizes == null ? List.of() : List.copyOf(prizes);
        }
    }

    public record ChallengeTokenRequest(
            @NotBlank String challengeToken
    ) {
    }

    public record FulfillRequest(
            String remark
    ) {
    }

    public record AdminDraw(
            Long id,
            Long campaignId,
            Long tierId,
            Long prizeId,
            Long orderId,
            String orderNo,
            Long userId,
            Integer productAmount,
            Integer prizeIndex,
            String prizeType,
            String prizeName,
            Integer discountAmount,
            Long productId,
            Long skuId,
            String imageUrl,
            Integer payableBefore,
            Integer payableAmount,
            String status,
            String relationStatus,
            String giftStockStatus,
            String voidReason,
            String orderStatus,
            String shareTriggeredAt,
            String drawnAt,
            String createdAt,
            String paidAt,
            String fulfilledAt,
            String fulfillmentRemark,
            List<Gift> gifts
    ) {
        public AdminDraw {
            gifts = gifts == null ? List.of() : List.copyOf(gifts);
        }
    }

    /**
     * StorefrontSnapshot 中只保存订单最终促销投影，不保存 challenge、权重和流水。
     */
    public record OrderPromotionProjection(
            Long drawId,
            Long prizeId,
            Integer prizeIndex,
            String prizeType,
            String prizeName,
            Integer discountAmount,
            Long productId,
            Long skuId,
            String imageUrl,
            Integer payableAmount,
            List<Gift> gifts,
            String status
    ) {
        public OrderPromotionProjection {
            gifts = gifts == null ? List.of() : List.copyOf(gifts);
        }

        public DrawResult toResult() {
            return new DrawResult(
                    drawId,
                    prizeId,
                    prizeIndex,
                    prizeType,
                    prizeName,
                    discountAmount == null ? 0 : discountAmount,
                    productId,
                    skuId,
                    imageUrl,
                    payableAmount,
                    gifts,
                    status
            );
        }
    }
}
