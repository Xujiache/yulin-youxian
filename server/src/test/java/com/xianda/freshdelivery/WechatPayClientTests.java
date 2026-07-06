package com.xianda.freshdelivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import com.xianda.freshdelivery.service.WechatPayClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class WechatPayClientTests {
    @Test
    void developmentModeReturnsMiniProgramPaymentParams() {
        WechatPayClient client = newClient();
        OrderDetailDto order = new OrderDetailDto(1L, "XD20260705001", "待支付", null, "今日 14:00-16:00", List.of(), 0, 0, 0, 100, 0, 0, "");

        PaymentDto payment = client.createJsapiPayment(order, "dev_openid");

        assertTrue(payment.developmentMode());
        assertEquals("prepay_id=development_XD20260705001", payment.packageValue());
        assertEquals("RSA", payment.signType());
    }

    @Test
    void directPaymentNotifyPayloadCanBeParsedInDevelopmentMode() {
        WechatPayClient client = newClient();

        PaymentNotifyRequest request = client.parsePaymentNotify("{\"orderNo\":\"XD1\",\"transactionId\":\"TX1\",\"tradeState\":\"SUCCESS\"}", null, null, null);

        assertEquals("XD1", request.orderNo());
        assertEquals("TX1", request.transactionId());
        assertEquals("SUCCESS", request.tradeState());
    }

    @Test
    void directRefundNotifyPayloadCanBeParsedInDevelopmentMode() {
        WechatPayClient client = newClient();

        RefundNotifyRequest request = client.parseRefundNotify("{\"refundNo\":\"RF1\",\"refundStatus\":\"SUCCESS\"}", null, null, null);

        assertEquals("RF1", request.refundNo());
        assertEquals("SUCCESS", request.refundStatus());
    }

    private WechatPayClient newClient() {
        WechatPayProperties properties = new WechatPayProperties();
        properties.setDevelopmentMode(true);
        return new WechatPayClient(properties);
    }
}
