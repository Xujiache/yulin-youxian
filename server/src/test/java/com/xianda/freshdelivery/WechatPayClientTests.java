package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.service.WechatPayClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class WechatPayClientTests {
    @Test
    void paymentRequiresCompleteWechatPayConfiguration() {
        WechatPayClient client = new WechatPayClient(new WechatPayProperties());
        OrderDetailDto order = new OrderDetailDto(1L, "XD20260705001", "待支付", null, "今日 14:00-16:00", List.of(), 0, 0, 0, 100, 0, 0, "", "", "", "", 1000L, List.of());

        assertThrows(BusinessException.class, () -> client.createJsapiPayment(order, "real-openid"));
    }

    @Test
    void rejectsUnsignedDirectPaymentNotifyPayload() {
        WechatPayClient client = new WechatPayClient(new WechatPayProperties());

        assertThrows(BusinessException.class, () -> client.parsePaymentNotify(
                "{\"orderNo\":\"XD1\",\"transactionId\":\"TX1\",\"tradeState\":\"SUCCESS\"}",
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void rejectsUnsignedDirectRefundNotifyPayload() {
        WechatPayClient client = new WechatPayClient(new WechatPayProperties());

        assertThrows(BusinessException.class, () -> client.parseRefundNotify(
                "{\"refundNo\":\"RF1\",\"refundStatus\":\"SUCCESS\"}",
                null,
                null,
                null,
                null
        ));
    }
}
