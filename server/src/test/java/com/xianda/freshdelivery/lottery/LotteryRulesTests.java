package com.xianda.freshdelivery.lottery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.lottery.LotteryModels.Campaign;
import com.xianda.freshdelivery.lottery.LotteryModels.DiscountMode;
import com.xianda.freshdelivery.lottery.LotteryModels.Prize;
import com.xianda.freshdelivery.lottery.LotteryModels.PrizeCode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class LotteryRulesTests {
    @Test
    void fourSlotsWithExactProbabilitySumPass() {
        LotteryRules.validate(campaign(LotteryCampaignFixtures.onlyNone()));
        LotteryRules.validate(campaign(List.of(
                LotteryCampaignFixtures.threshold(PrizeCode.FIRST, 1, 3000, 800),
                LotteryCampaignFixtures.percentage(PrizeCode.SECOND, 2, 2000, 600),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9997)
        )));
    }

    @Test
    void missingDuplicateOrUnknownSlotsAreRejected() {
        List<Prize> missing = new ArrayList<>(LotteryCampaignFixtures.onlyNone());
        missing.remove(3);
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(missing))).code());

        List<Prize> duplicate = List.of(
                LotteryCampaignFixtures.inactive(PrizeCode.FIRST),
                LotteryCampaignFixtures.inactive(PrizeCode.FIRST),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(10_000)
        );
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(duplicate))).code());

        Prize unknown = new Prize(
                null, "DISCOUNT", "未知", null, null, null, null, 0, 0, 0, true, 10,
                "UNKNOWN", 0, "PERCENTAGE", null, null, null, null
        );
        assertEquals(400, assertThrows(
                BusinessException.class,
                () -> LotteryRules.validate(campaign(List.of(
                        unknown,
                        LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                        LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                        LotteryCampaignFixtures.thankYou(10_000)
                )))
        ).code());
    }

    @Test
    void probabilitySumMustBeExactlyOneHundredPercent() {
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                LotteryCampaignFixtures.inactive(PrizeCode.FIRST),
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9999)
        )))).code());
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                LotteryCampaignFixtures.inactive(PrizeCode.FIRST),
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(10_001)
        )))).code());
    }

    @Test
    void zeroAndFullProbabilityBoundariesAreAccepted() {
        LotteryRules.validate(campaign(LotteryCampaignFixtures.onlyNone()));
        LotteryRules.validate(campaign(List.of(
                LotteryCampaignFixtures.percentage(PrizeCode.FIRST, 10_000, 2000, 1000),
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(0)
        )));
    }

    @Test
    void thankYouCannotCarryDiscountFields() {
        Prize invalid = Prize.fixed(null, PrizeCode.NONE, 10_000, DiscountMode.NONE, 100, 10, 2000, 100);
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                LotteryCampaignFixtures.inactive(PrizeCode.FIRST),
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                invalid
        )))).code());
    }

    @Test
    void positiveProbabilityRequiresCompleteModeFields() {
        Prize missingThreshold = Prize.fixed(
                null, PrizeCode.FIRST, 1000, DiscountMode.THRESHOLD, null, null, null, null
        );
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                missingThreshold,
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9000)
        )))).code());

        Prize yNotLessThanX = LotteryCampaignFixtures.threshold(PrizeCode.FIRST, 1000, 1000, 1000);
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                yNotLessThanX,
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9000)
        )))).code());

        Prize zeroRate = Prize.fixed(null, PrizeCode.FIRST, 1000, DiscountMode.PERCENTAGE, null, null, 0, 100);
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                zeroRate,
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9000)
        )))).code());

        Prize overRate = Prize.fixed(null, PrizeCode.FIRST, 1000, DiscountMode.PERCENTAGE, null, null, 10_001, 100);
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                overRate,
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9000)
        )))).code());

        Prize missingCap = Prize.fixed(null, PrizeCode.FIRST, 1000, DiscountMode.PERCENTAGE, null, null, 2000, null);
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                missingCap,
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9000)
        )))).code());
    }

    @Test
    void mixedModeFieldsAreRejected() {
        Prize mixed = Prize.fixed(null, PrizeCode.FIRST, 1000, DiscountMode.THRESHOLD, 3000, 800, 2000, 600);
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(campaign(List.of(
                mixed,
                LotteryCampaignFixtures.inactive(PrizeCode.SECOND),
                LotteryCampaignFixtures.inactive(PrizeCode.THIRD),
                LotteryCampaignFixtures.thankYou(9000)
        )))).code());
    }

    @Test
    void zeroProbabilityAllowsEmptyRules() {
        LotteryRules.validate(campaign(LotteryCampaignFixtures.onlyNone()));
    }

    @Test
    void actualDiscountUsesFloorCapAndThreshold() {
        Prize percentage = LotteryCampaignFixtures.percentage(PrizeCode.FIRST, 1000, 2000, 3000);
        assertEquals(OptionalInt.of(1000), LotteryRules.actualDiscount(percentage, 5000));
        assertEquals(OptionalInt.of(600), LotteryRules.actualDiscount(
                LotteryCampaignFixtures.percentage(PrizeCode.FIRST, 1000, 2000, 600), 5000));
        assertEquals(OptionalInt.of(599), LotteryRules.actualDiscount(percentage, 2999));
        assertEquals(OptionalInt.empty(), LotteryRules.actualDiscount(percentage, 4));
        assertEquals(OptionalInt.of(1), LotteryRules.actualDiscount(
                LotteryCampaignFixtures.percentage(PrizeCode.FIRST, 1000, 10_000, 10_000), 2));

        Prize threshold = LotteryCampaignFixtures.threshold(PrizeCode.FIRST, 1000, 3000, 800);
        assertTrue(LotteryRules.actualDiscount(threshold, 2999).isEmpty());
        assertEquals(OptionalInt.of(800), LotteryRules.actualDiscount(threshold, 3000));
        assertEquals(OptionalInt.of(800), LotteryRules.actualDiscount(threshold, 5000));
        assertEquals(OptionalInt.of(0), LotteryRules.actualDiscount(LotteryCampaignFixtures.thankYou(1000), 5000));
    }

    @Test
    void probabilitySelectionUsesSecureRandomTicket() {
        Prize first = LotteryCampaignFixtures.percentage(PrizeCode.FIRST, 1, 2000, 1000);
        Prize none = LotteryCampaignFixtures.thankYou(3);
        assertEquals(first, LotteryRules.selectByProbability(List.of(first, none), new TicketRandom(0)));
        assertEquals(none, LotteryRules.selectByProbability(List.of(first, none), new TicketRandom(1)));
        assertEquals(none, LotteryRules.selectByProbability(List.of(first, none), new TicketRandom(3)));
    }

    @Test
    void oversizedCampaignTextIsRejected() {
        Campaign longName = new Campaign(
                1L, true, "活".repeat(129), null, null, 1, null,
                "分享标题", "分享描述", null, List.of(), LotteryCampaignFixtures.onlyNone()
        );
        Campaign longDescription = new Campaign(
                1L, true, "随机减免", null, null, 1, null,
                "分享标题", "描".repeat(513), null, List.of(), LotteryCampaignFixtures.onlyNone()
        );
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(longName)).code());
        assertEquals(400, assertThrows(BusinessException.class, () -> LotteryRules.validate(longDescription)).code());
    }

    @Test
    void matchingTierIgnoresTheGlobalPool() {
        assertFalse(LotteryRules.actualDiscount(
                LotteryCampaignFixtures.threshold(PrizeCode.FIRST, 1000, 3000, 800), 2999).isPresent());
    }

    private Campaign campaign(List<Prize> prizes) {
        return new Campaign(
                1L, true, "随机减免", null, null, 1, null,
                "分享标题", "分享描述", null, List.of(), prizes
        );
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
