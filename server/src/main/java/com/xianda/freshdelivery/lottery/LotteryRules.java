package com.xianda.freshdelivery.lottery;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.DiscountMode;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeCode;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeType;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

public final class LotteryRules {
    /** 与 V10 的列宽保持一致，超长会在 MySQL 侧报 1406 并变成 500。 */
    private static final int NAME_MAX_LENGTH = 128;
    private static final int TEXT_MAX_LENGTH = 512;
    public static final int PROBABILITY_SCALE = 10_000;

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
        validateFixedPrizes(campaign.prizes());
    }

    public static void validateFixedPrizes(List<Prize> prizes) {
        if (prizes == null || prizes.size() != PrizeCode.values().length) {
            throw new BusinessException(400, "必须配置一等奖、二等奖、三等奖和谢谢惠顾四个固定奖项");
        }
        Set<PrizeCode> seen = EnumSet.noneOf(PrizeCode.class);
        int totalProbability = 0;
        for (Prize prize : prizes) {
            if (prize == null) {
                throw new BusinessException(400, "奖项配置不能为空");
            }
            PrizeCode code = prizeCode(prize.prizeCode());
            if (!seen.add(code)) {
                throw new BusinessException(400, "固定奖项槽位重复：" + code.displayName());
            }
            int probability = requireProbability(prize.probabilityBp(), code);
            totalProbability += probability;
            validatePrizeRule(code, prize, probability);
        }
        for (PrizeCode code : PrizeCode.values()) {
            if (!seen.contains(code)) {
                throw new BusinessException(400, "缺少固定奖项：" + code.displayName());
            }
        }
        if (totalProbability != PROBABILITY_SCALE) {
            throw new BusinessException(400, "四个奖项的中奖概率总和必须等于 100.00%");
        }
    }

    private static void validatePrizeRule(PrizeCode code, Prize prize, int probability) {
        DiscountMode mode = discountMode(prize.discountMode());
        if (code == PrizeCode.NONE) {
            if (mode != DiscountMode.NONE) {
                throw new BusinessException(400, "谢谢惠顾不能配置减免");
            }
            if (hasPositive(prize.thresholdAmount())
                    || hasPositive(prize.fixedDiscountAmount())
                    || hasPositive(prize.discountRateBp())
                    || hasPositive(prize.maxDiscountAmount())
                    || hasPositive(prize.discountAmount())) {
                throw new BusinessException(400, "谢谢惠顾不能配置任何减免字段");
            }
            return;
        }
        if (mode != DiscountMode.THRESHOLD && mode != DiscountMode.PERCENTAGE) {
            throw new BusinessException(400, code.displayName() + "只能选择满减或百分比模式");
        }
        if (probability == 0) {
            return;
        }
        if (mode == DiscountMode.THRESHOLD) {
            if (!hasPositive(prize.thresholdAmount()) || !hasPositive(prize.fixedDiscountAmount())) {
                throw new BusinessException(400, code.displayName() + "满减模式必须填写满多少元和减多少元");
            }
            if (prize.fixedDiscountAmount() >= prize.thresholdAmount()) {
                throw new BusinessException(400, code.displayName() + "的减免金额必须小于满减门槛");
            }
            if (hasPositive(prize.discountRateBp()) || hasPositive(prize.maxDiscountAmount())) {
                throw new BusinessException(400, code.displayName() + "满减模式不能同时填写百分比字段");
            }
            return;
        }
        if (prize.discountRateBp() == null
                || prize.discountRateBp() < 1
                || prize.discountRateBp() > PROBABILITY_SCALE) {
            throw new BusinessException(400, code.displayName() + "的减免比例必须在 0.01% 到 100.00% 之间");
        }
        if (!hasPositive(prize.maxDiscountAmount())) {
            throw new BusinessException(400, code.displayName() + "百分比模式必须设置最大减免金额");
        }
        if (hasPositive(prize.thresholdAmount()) || hasPositive(prize.fixedDiscountAmount())) {
            throw new BusinessException(400, code.displayName() + "百分比模式不能同时填写满减字段");
        }
    }

    public static OptionalInt actualDiscount(Prize prize, int payableBefore) {
        if (prize == null) {
            return OptionalInt.empty();
        }
        DiscountMode mode = discountMode(prize.discountMode());
        if (mode == DiscountMode.NONE || prizeCodeOrNull(prize.prizeCode()) == PrizeCode.NONE) {
            return OptionalInt.of(0);
        }
        if (payableBefore <= 1) {
            return OptionalInt.empty();
        }
        if (mode == DiscountMode.THRESHOLD) {
            int threshold = value(prize.thresholdAmount(), 0);
            int fixed = value(prize.fixedDiscountAmount(), 0);
            if (threshold <= 0 || fixed <= 0 || payableBefore < threshold) {
                return OptionalInt.empty();
            }
            int actual = Math.min(fixed, payableBefore - 1);
            return actual > 0 ? OptionalInt.of(actual) : OptionalInt.empty();
        }
        if (mode == DiscountMode.PERCENTAGE) {
            int rate = value(prize.discountRateBp(), 0);
            int cap = value(prize.maxDiscountAmount(), 0);
            if (rate <= 0 || cap <= 0) {
                return OptionalInt.empty();
            }
            long raw = ((long) payableBefore * rate) / PROBABILITY_SCALE;
            if (raw <= 0) {
                return OptionalInt.empty();
            }
            int actual = (int) Math.min(raw, Math.min((long) cap, payableBefore - 1L));
            return actual > 0 ? OptionalInt.of(actual) : OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    public static Prize selectByProbability(List<Prize> prizes, SecureRandom random) {
        List<Prize> candidates = new ArrayList<>(prizes == null ? List.of() : prizes);
        long total = candidates.stream().mapToLong(prize -> value(prize.probabilityBp(), 0)).sum();
        if (total <= 0 || total > Integer.MAX_VALUE) {
            throw new BusinessException(409, "当前没有可抽取的奖项");
        }
        int ticket = random.nextInt((int) total);
        int cursor = 0;
        for (Prize prize : candidates) {
            cursor += value(prize.probabilityBp(), 0);
            if (ticket < cursor) {
                return prize;
            }
        }
        throw new IllegalStateException("奖项概率计算失败");
    }

    public static List<Prize> sortedFixedPrizes(List<Prize> prizes) {
        return (prizes == null ? List.<Prize>of() : prizes).stream()
                .sorted(Comparator
                        .comparingInt((Prize prize) -> {
                            PrizeCode code = prizeCodeOrNull(prize.prizeCode());
                            return code == null ? 100 : code.slotIndex();
                        })
                        .thenComparingInt(prize -> value(prize.sortOrder(), 100))
                        .thenComparingLong(prize -> prize.id() == null ? Long.MAX_VALUE : prize.id()))
                .toList();
    }

    public static Tier matchingTier(Campaign campaign, int productAmount) {
        if (campaign == null) {
            return null;
        }
        return campaign.tiers().stream()
                .filter(tier -> !Boolean.FALSE.equals(tier.enabled()))
                .filter(tier -> !"GLOBAL".equals(tier.poolCode()))
                .filter(tier -> tier.minProductAmount() != null && productAmount >= tier.minProductAmount())
                .filter(tier -> tier.maxProductAmount() == null || productAmount < tier.maxProductAmount())
                .sorted(Comparator
                        .comparingInt((Tier tier) -> value(tier.sortOrder(), 100))
                        .thenComparingInt(tier -> value(tier.minProductAmount(), 0)))
                .findFirst()
                .orElse(null);
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

    public static PrizeCode prizeCode(String value) {
        PrizeCode code = prizeCodeOrNull(value);
        if (code == null) {
            throw new BusinessException(400, "奖项槽位仅支持 FIRST、SECOND、THIRD、NONE");
        }
        return code;
    }

    public static PrizeCode prizeCodeOrNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return PrizeCode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static DiscountMode discountMode(String value) {
        if (!hasText(value)) {
            return DiscountMode.NONE;
        }
        try {
            return DiscountMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "减免模式仅支持 THRESHOLD、PERCENTAGE、NONE");
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

    private static int requireProbability(Integer probabilityBp, PrizeCode code) {
        if (probabilityBp == null || probabilityBp < 0 || probabilityBp > PROBABILITY_SCALE) {
            throw new BusinessException(400, code.displayName() + "的中奖概率必须在 0% 到 100.00% 之间");
        }
        return probabilityBp;
    }

    private static void ensureLength(String value, int maxLength, String fieldName) {
        if (value != null && value.trim().length() > maxLength) {
            throw new BusinessException(400, fieldName + "不能超过 " + maxLength + " 个字符");
        }
    }

    private static boolean hasPositive(Integer value) {
        return value != null && value > 0;
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
