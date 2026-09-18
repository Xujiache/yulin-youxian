package com.xianda.freshdelivery.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RefundReasonSummaryTests {
    @Test void reasonFitsWechatByteLimitWithoutBreakingChineseOrEmoji() {
        String reason = "商品损坏：".repeat(12) + "🥬";
        String result = WechatPayClient.refundReasonSummary(reason);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 80);
        assertTrue(reason.startsWith(result));
        assertEquals("a".repeat(79), WechatPayClient.refundReasonSummary("a".repeat(79) + "🥬"));
        assertEquals("没货", WechatPayClient.refundReasonSummary("没货"));
    }
}
