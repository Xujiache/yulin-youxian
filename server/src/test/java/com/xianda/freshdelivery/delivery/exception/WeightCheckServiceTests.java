package com.xianda.freshdelivery.delivery.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.delivery.account.DeliveryConfigService;
import com.xianda.freshdelivery.delivery.account.MutableClock;
import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.dto.WeightCheckDto;
import com.xianda.freshdelivery.delivery.dto.WeightCheckJudgeRequest;
import com.xianda.freshdelivery.delivery.repository.DeliveryConfigDao;
import com.xianda.freshdelivery.delivery.settlement.A6Fixtures;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WeightCheckServiceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 11, 0, 0);

    private WeightCheckService weightCheckService;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = A6Fixtures.database("a6_weight_check");
        weightCheckService = new WeightCheckService(
                new ExceptionWeightCheckDao(jdbcTemplate),
                new DeliveryConfigService(new DeliveryConfigDao(jdbcTemplate)),
                new MutableClock(NOW));
    }

    @Test
    void twoPercentDifferencePasses() {
        WeightCheckDto dto = submit("1.000", "0.980", 1000);
        assertEquals(WeightCheckService.VERDICT_PASS, dto.verdict());
        assertEquals(2.0d, dto.diffPercent());
        assertEquals(0, dto.refundAmount());
    }

    @Test
    void fivePercentDifferenceGoesToManualReview() {
        WeightCheckDto dto = submit("1.000", "0.950", 1000);
        assertEquals(WeightCheckService.VERDICT_MANUAL_REVIEW, dto.verdict());
        assertEquals(5.0d, dto.diffPercent());
        assertEquals(0, dto.refundAmount());
    }

    @Test
    void tenPercentDifferenceTriggersAutoRefundAmountOnly() {
        WeightCheckDto dto = submit("1.000", "0.900", 1000);
        assertEquals(WeightCheckService.VERDICT_AUTO_REFUND, dto.verdict());
        assertEquals(10.0d, dto.diffPercent());
        assertEquals(100, dto.refundAmount());
    }

    @Test
    void exactlyAtToleranceStillPasses() {
        assertEquals(WeightCheckService.VERDICT_PASS, submit("1.000", "0.970", 1000).verdict());
    }

    @Test
    void exactlyAtAutoRefundThresholdRefunds() {
        assertEquals(WeightCheckService.VERDICT_AUTO_REFUND, submit("1.000", "0.920", 1000).verdict());
    }

    @Test
    void refundAmountIsZeroWithoutUnitPrice() {
        assertEquals(0, submit("2.000", "1.500", null).refundAmount());
    }

    @Test
    void manualJudgementOverridesVerdictAndRecordsOperator() {
        WeightCheckDto submitted = submit("1.000", "0.950", 1000);
        WeightCheckDto judged = weightCheckService.judge(submitted.id(),
                new WeightCheckJudgeRequest(WeightCheckService.VERDICT_AUTO_REFUND, 380, "复核后同意退差价"), "客服甲");
        assertEquals(WeightCheckService.VERDICT_AUTO_REFUND, judged.verdict());
        assertEquals(380, judged.refundAmount());
        assertEquals("客服甲", judged.handledBy());
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(DeliveryException.class, () -> submit("0.000", "0.500", 1000));
        assertThrows(DeliveryException.class,
                () -> weightCheckService.judge(1L, new WeightCheckJudgeRequest("UNKNOWN", 0, null), "客服甲"));
    }

    private WeightCheckDto submit(String picked, String customer, Integer unitPricePerKg) {
        return weightCheckService.submit(9001L, null, null, "有机菜心",
                BigDecimal.ONE, new BigDecimal(picked), new BigDecimal(customer), unitPricePerKg, null, null);
    }
}
