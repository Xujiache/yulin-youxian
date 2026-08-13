package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeType;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LotteryRules {
    /** 与 V10 的列宽保持一致，超长会在 MySQL 侧报 1406 并变成 500。 */
    private static final int NAME_MAX_LENGTH = 128;
    private static final int TEXT_MAX_LENGTH = 512;

    private LotteryRules() {
    }

    public static void validate(Campaign campaign) {
        if (campaign == null) {
            throw new BusinessException(400, "活动配置不能为空");
        }
        if (!hasText(campaign.name())) {
            throw new BusinessException(400, "活动名称不能为空");
        }
        ensureLength(campaign.name(), NAME_MAX_LENGTH, "活动名称");
        ensureLength(campaign.shareTitle(), NAME_MAX_LENGTH, "分享标题");
        ensureLength(campaign.shareDescription(), TEXT_MAX_LENGTH, "分享描述");
        ensureLength(campaign.shareImageUrl(), TEXT_MAX_LENGTH, "分享图片地址");
        if (campaign.dailyUserLimit() == null || campaign.dailyUserLimit() < 1) {
            throw new BusinessException(400, "用户每日抽奖次数必须大于 0");
        }
        if (campaign.dailyBudgetAmount() != null && campaign.dailyBudgetAmount() < 0) {
            throw new BusinessException(400, "每日预算不能小于 0");
        }
        LocalDateTime startAt = parseTime(campaign.startAt(), "活动开始时间");
        LocalDateTime endAt = parseTime(campaign.endAt(), "活动结束时间");
        if (startAt != null && endAt != null && !startAt.isBefore(endAt)) {
            throw new BusinessException(400, "活动结束时间必须晚于开始时间");
        }

        List<Tier> enabledTiers = campaign.tiers().stream()
                .filter(tier -> tier != null && !Boolean.FALSE.equals(tier.enabled()))
                .sorted(Comparator.comparingInt(tier -> value(tier.minProductAmount(), -1)))
                .toList();
        if (Boolean.TRUE.equals(campaign.enabled()) && enabledTiers.isEmpty()) {
            throw new BusinessException(400, "启用活动前至少配置一个启用阶梯");
        }
        Set<Long> tierIds = new HashSet<>();
        Set<Long> prizeIds = new HashSet<>();
        for (int index = 0; index < enabledTiers.size(); index++) {
            Tier tier = enabledTiers.get(index);
            validateTier(tier, index == enabledTiers.size() - 1, tierIds, prizeIds);
            if (index > 0) {
                Tier previous = enabledTiers.get(index - 1);
                if (previous.maxProductAmount() == null
                        || previous.maxProductAmount() > tier.minProductAmount()) {
                    throw new BusinessException(400, "启用阶梯金额区间不能重叠");
                }
            }
        }
        for (Tier tier : campaign.tiers()) {
            if (tier == null || !Boolean.FALSE.equals(tier.enabled())) {
                continue;
            }
            validateTier(tier, true, tierIds, prizeIds);
        }
    }

    private static void validateTier(
            Tier tier,
            boolean mayHaveOpenEnd,
            Set<Long> tierIds,
            Set<Long> prizeIds
    ) {
        if (!hasText(tier.name())) {
            throw new BusinessException(400, "阶梯名称不能为空");
        }
        ensureLength(tier.name(), NAME_MAX_LENGTH, "阶梯名称");
        if (tier.id() != null && !tierIds.add(tier.id())) {
            throw new BusinessException(400, "阶梯 ID 重复");
        }
        if (tier.minProductAmount() == null || tier.minProductAmount() < 0) {
            throw new BusinessException(400, "阶梯最低商品金额不能小于 0");
        }
        if (tier.maxProductAmount() == null && !mayHaveOpenEnd && !Boolean.FALSE.equals(tier.enabled())) {
            throw new BusinessException(400, "仅最后一个启用阶梯可不设置最高金额");
        }
        if (tier.maxProductAmount() != null
                && tier.maxProductAmount() <= tier.minProductAmount()) {
            throw new BusinessException(400, "阶梯最高金额必须大于最低金额");
        }
        long totalWeight = 0;
        int enabledPrizeCount = 0;
        for (Prize prize : tier.prizes()) {
            if (prize == null) {
                throw new BusinessException(400, "奖项配置不能为空");
            }
            if (prize.id() != null && !prizeIds.add(prize.id())) {
                throw new BusinessException(400, "奖项 ID 重复");
            }
            PrizeType type = prizeType(prize.type());
            if (!hasText(prize.name())) {
                throw new BusinessException(400, "奖项名称不能为空");
            }
            ensureLength(prize.name(), NAME_MAX_LENGTH, "奖项名称");
            ensureLength(prize.imageUrl(), TEXT_MAX_LENGTH, "奖项图片地址");
            if (prize.weight() == null || prize.weight() < 0) {
                throw new BusinessException(400, "奖项权重不能小于 0");
            }
            if (!Boolean.FALSE.equals(prize.enabled()) && prize.weight() == 0) {
                throw new BusinessException(400, "启用奖项的权重必须大于 0");
            }
            if (prize.stockTotal() != null && prize.stockTotal() < 0) {
                throw new BusinessException(400, "营销库存不能小于 0");
            }
            if (type == PrizeType.DISCOUNT
                    && (prize.discountAmount() == null || prize.discountAmount() <= 0)) {
                throw new BusinessException(400, "减免奖项金额必须大于 0");
            }
            // 实际减免是 min(配置额, 应付 - 1)，转盘展示的却是名义金额。减免额不小于阶梯
            // 下限时就会出现「减 5 元实际减 3.99」，所以配置阶段直接拦掉。
            if (type == PrizeType.DISCOUNT
                    && prize.discountAmount() != null
                    && tier.minProductAmount() != null
                    && prize.discountAmount() >= tier.minProductAmount()) {
                throw new BusinessException(
                        400,
                        "减免奖项金额必须小于所属阶梯的最低商品金额，否则实际减免会被压缩"
                );
            }
            if (type == PrizeType.GOODS && prize.productId() == null) {
                throw new BusinessException(400, "赠品奖项必须关联商品");
            }
            if (type == PrizeType.GOODS && prize.stockTotal() == null) {
                throw new BusinessException(400, "赠品奖项必须设置营销库存");
            }
            if (type == PrizeType.GOODS && prize.stockRemaining() != null
                    && (prize.stockRemaining() < 0 || prize.stockRemaining() > prize.stockTotal())) {
                throw new BusinessException(400, "赠品剩余库存必须在 0 到总库存之间");
            }
            if (!Boolean.FALSE.equals(prize.enabled())) {
                enabledPrizeCount++;
                totalWeight += prize.weight();
            }
            if (totalWeight > Integer.MAX_VALUE) {
                throw new BusinessException(400, "同一阶梯奖项权重总和过大");
            }
        }
        if (!Boolean.FALSE.equals(tier.enabled()) && enabledPrizeCount == 0) {
            throw new BusinessException(400, "启用阶梯至少需要一个启用奖项");
        }
    }

    public static Tier matchingTier(Campaign campaign, int productAmount) {
        if (campaign == null) {
            return null;
        }
        return campaign.tiers().stream()
                .filter(tier -> !Boolean.FALSE.equals(tier.enabled()))
                .filter(tier -> tier.minProductAmount() != null && productAmount >= tier.minProductAmount())
                .filter(tier -> tier.maxProductAmount() == null || productAmount < tier.maxProductAmount())
                .sorted(Comparator
                        .comparingInt((Tier tier) -> value(tier.sortOrder(), 100))
                        .thenComparingInt(tier -> value(tier.minProductAmount(), 0)))
                .findFirst()
                .orElse(null);
    }

    public static Prize weightedPrize(List<Prize> prizes, SecureRandom random) {
        List<Prize> candidates = new ArrayList<>(prizes == null ? List.of() : prizes);
        long total = candidates.stream().mapToLong(prize -> prize.weight() == null ? 0 : prize.weight()).sum();
        if (total <= 0 || total > Integer.MAX_VALUE) {
            throw new BusinessException(409, "当前没有可抽取的奖项");
        }
        int ticket = random.nextInt((int) total);
        int cursor = 0;
        for (Prize prize : candidates) {
            cursor += prize.weight();
            if (ticket < cursor) {
                return prize;
            }
        }
        throw new IllegalStateException("奖项权重计算失败");
    }

    public static boolean isCampaignActive(Campaign campaign, LocalDateTime now) {
        if (campaign == null || !Boolean.TRUE.equals(campaign.enabled())) {
            return false;
        }
        LocalDateTime startAt = parseTime(campaign.startAt(), "活动开始时间");
        LocalDateTime endAt = parseTime(campaign.endAt(), "活动结束时间");
        return (startAt == null || !now.isBefore(startAt))
                && (endAt == null || now.isBefore(endAt));
    }

    public static PrizeType prizeType(String value) {
        try {
            return PrizeType.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "奖项类型仅支持 DISCOUNT、GOODS、NONE");
        }
    }

    public static LocalDateTime parseTime(String value, String fieldName) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(400, fieldName + "格式无效");
        }
    }

    private static void ensureLength(String value, int maxLength, String fieldName) {
        if (value != null && value.trim().length() > maxLength) {
            throw new BusinessException(400, fieldName + "不能超过 " + maxLength + " 个字符");
        }
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
