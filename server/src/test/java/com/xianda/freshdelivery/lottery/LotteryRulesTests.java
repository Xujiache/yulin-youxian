package com.xianda.freshdelivery.lottery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.Tier;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

class LotteryRulesTests {
    @Test
    void tierIntervalsAreLeftClosedRightOpen() {
        Campaign campaign = campaign(List.of(
                tier(1L, 0, 1000, 10),
                tier(2L, 1000, null, 20)
        ));

        assertEquals(1L, LotteryRules.matchingTier(campaign, 0).id());
        assertEquals(1L, LotteryRules.matchingTier(campaign, 999).id());
        assertEquals(2L, LotteryRules.matchingTier(campaign, 1000).id());
        assertEquals(2L, LotteryRules.matchingTier(campaign, 999999).id());
    }

    @Test
    void validationRejectsOverlappingAndNonFinalOpenEndedTiers() {
        Campaign overlap = campaign(List.of(
                tier(1L, 0, 1001, 10),
                tier(2L, 1000, null, 20)
        ));
        Campaign prematureOpenEnd = campaign(List.of(
                tier(1L, 0, null, 10),
                tier(2L, 1000, 2000, 20)
        ));

        assertThrows(BusinessException.class, () -> LotteryRules.validate(overlap));
        assertThrows(BusinessException.class, () -> LotteryRules.validate(prematureOpenEnd));
    }

    @Test
    void integerWeightSelectionUsesSecureRandomTicket() {
        Prize first = prize(1L, "一等奖", 1);
        Prize second = prize(2L, "二等奖", 3);

        assertEquals(first, LotteryRules.weightedPrize(List.of(first, second), new TicketRandom(0)));
        assertEquals(second, LotteryRules.weightedPrize(List.of(first, second), new TicketRandom(1)));
        assertEquals(second, LotteryRules.weightedPrize(List.of(first, second), new TicketRandom(3)));
    }

    @Test
    void disabledPrizeMayKeepZeroWeight() {
        Prize disabled = new Prize(
                2L, "NONE", "暂时停用", null, null, null, null,
                0, 0, 0, false, 20
        );
        Campaign campaign = campaign(List.of(new Tier(
                1L,
                "全部订单",
                0,
                null,
                true,
                10,
                List.of(prize(1L, "谢谢参与", 1), disabled)
        )));

        LotteryRules.validate(campaign);
    }

    private Campaign campaign(List<Tier> tiers) {
        return new Campaign(
                1L, true, "随机减免", null, null, 1, null,
                "分享标题", "分享描述", null, tiers
        );
    }

    private Tier tier(Long id, int min, Integer max, int sortOrder) {
        return new Tier(id, "阶梯" + id, min, max, true, sortOrder, List.of(prize(id, "未中奖", 1)));
    }

    private Prize prize(Long id, String name, int weight) {
        return new Prize(id, "NONE", name, null, null, null, null, weight, 0, 0, true, 10);
    }

    private static final class TicketRandom extends SecureRandom {
        private final int ticket;

        private TicketRandom(int ticket) {
            this.ticket = ticket;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(ticket, bound - 1);
        }
    }
}
