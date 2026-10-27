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

    public enum PrizeCode {
        FIRST("一等奖", 10, 0),
        SECOND("二等奖", 20, 1),
        THIRD("三等奖", 30, 2),
        NONE("谢谢惠顾", 40, 3);

        private final String displayName;
        private final int sortOrder;
        private final int slotIndex;

        PrizeCode(String displayName, int sortOrder, int slotIndex) {
            this.displayName = displayName;
            this.sortOrder = sortOrder;
            this.slotIndex = slotIndex;
        }

        public String displayName() {
            return displayName;
        }

        public int sortOrder() {
            return sortOrder;
        }

        public int slotIndex() {
            return slotIndex;
        }
    }

    public enum DiscountMode {
        THRESHOLD,
        PERCENTAGE,
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
            List<Tier> tiers,
            List<Prize> prizes
    ) {
        public Campaign {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
            prizes = prizes == null ? List.of() : List.copyOf(prizes);
        }

        public Campaign(
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
            this(
                    id,
                    enabled,
                    name,
                    startAt,
                    endAt,
                    dailyUserLimit,
                    dailyBudgetAmount,
                    shareTitle,
                    shareDescription,
                    shareImageUrl,
                    tiers,
                    List.of()
            );
        }
    }

    public record Tier(
            Long id,
            String name,
            Integer minProductAmount,
            Integer maxProductAmount,
            Boolean enabled,
            Integer sortOrder,
            List<Prize> prizes,
            String poolCode
    ) {
        public Tier {
            prizes = prizes == null ? List.of() : List.copyOf(prizes);
        }

        public Tier(
                Long id,
                String name,
                Integer minProductAmount,
                Integer maxProductAmount,
                Boolean enabled,
                Integer sortOrder,
                List<Prize> prizes
        ) {
            this(id, name, minProductAmount, maxProductAmount, enabled, sortOrder, prizes, null);
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
            Integer sortOrder,
            String prizeCode,
            Integer probabilityBp,
            String discountMode,
            Integer thresholdAmount,
            Integer fixedDiscountAmount,
            Integer discountRateBp,
            Integer maxDiscountAmount
    ) {
        public Prize(
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
            this(
                    id,
                    type,
                    name,
                    discountAmount,
                    productId,
                    skuId,
                    imageUrl,
                    weight,
                    stockTotal,
                    stockRemaining,
                    enabled,
                    sortOrder,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        public static Prize fixed(
                Long id,
                PrizeCode code,
                int probabilityBp,
                DiscountMode mode,
                Integer thresholdAmount,
                Integer fixedDiscountAmount,
                Integer discountRateBp,
                Integer maxDiscountAmount
        ) {
            String type = code == PrizeCode.NONE ? PrizeType.NONE.name() : PrizeType.DISCOUNT.name();
            Integer storedDiscount = mode == DiscountMode.THRESHOLD ? fixedDiscountAmount : null;
            return new Prize(
                    id,
                    type,
                    code.displayName(),
                    storedDiscount,
                    null,
                    null,
                    null,
                    probabilityBp,
                    0,
                    0,
                    true,
                    code.sortOrder(),
                    code.name(),
                    probabilityBp,
                    mode.name(),
                    thresholdAmount,
                    fixedDiscountAmount,
                    discountRateBp,
                    maxDiscountAmount
            );
        }
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
            Integer sortOrder,
            String prizeCode
    ) {
        public PublicPrize(
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
            this(id, type, name, discountAmount, productId, skuId, imageUrl, enabled, sortOrder, null);
        }
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
            String status,
            String prizeCode
    ) {
        public DrawResult {
            gifts = gifts == null ? List.of() : List.copyOf(gifts);
        }

        public DrawResult(
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
            this(
                    drawId,
                    prizeId,
                    prizeIndex,
                    prizeType,
                    prizeName,
                    discountAmount,
                    productId,
                    skuId,
                    imageUrl,
                    payableAmount,
                    gifts,
                    status,
                    null
            );
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
            List<Gift> gifts,
            String prizeCode
    ) {
        public AdminDraw {
            gifts = gifts == null ? List.of() : List.copyOf(gifts);
        }

        public AdminDraw(
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
            this(
                    id,
                    campaignId,
                    tierId,
                    prizeId,
                    orderId,
                    orderNo,
                    userId,
                    productAmount,
                    prizeIndex,
                    prizeType,
                    prizeName,
                    discountAmount,
                    productId,
                    skuId,
                    imageUrl,
                    payableBefore,
                    payableAmount,
                    status,
                    relationStatus,
                    giftStockStatus,
                    voidReason,
                    orderStatus,
                    shareTriggeredAt,
                    drawnAt,
                    createdAt,
                    paidAt,
                    fulfilledAt,
                    fulfillmentRemark,
                    gifts,
                    null
            );
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
            String status,
            String prizeCode
    ) {
        public OrderPromotionProjection {
            gifts = gifts == null ? List.of() : List.copyOf(gifts);
        }

        public OrderPromotionProjection(
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
            this(
                    drawId,
                    prizeId,
                    prizeIndex,
                    prizeType,
                    prizeName,
                    discountAmount,
                    productId,
                    skuId,
                    imageUrl,
                    payableAmount,
                    gifts,
                    status,
                    null
            );
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
                    status,
                    prizeCode
            );
        }
    }
}
