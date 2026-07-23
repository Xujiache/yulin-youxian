package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPayClient;
import com.xianda.freshdelivery.service.WechatPaymentService;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wx")
public class WxPaymentController {
    private final WechatPayProperties wechatPayProperties;
    private final StorefrontService storefrontService;
    private final WechatPayClient wechatPayClient;
    private final WechatPaymentService wechatPaymentService;

    public WxPaymentController(WechatPayProperties wechatPayProperties, StorefrontService storefrontService, WechatPayClient wechatPayClient, WechatPaymentService wechatPaymentService) {
        this.wechatPayProperties = wechatPayProperties;
        this.storefrontService = storefrontService;
        this.wechatPayClient = wechatPayClient;
        this.wechatPaymentService = wechatPaymentService;
    }

    @PostMapping("/payments/wechat/notify")
    public Map<String, String> paymentNotify(
            @RequestBody String body,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature) {
        PaymentNotifyRequest request = wechatPayClient.parsePaymentNotify(body, timestamp, nonce, signature);
        wechatPaymentService.confirmPayment(request);
        return Map.of("code", "SUCCESS", "message", "成功");
    }

    @PostMapping("/refunds/wechat/notify")
    public Map<String, String> refundNotify(
            @RequestBody String body,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature) {
        RefundNotifyRequest request = wechatPayClient.parseRefundNotify(body, timestamp, nonce, signature);
        storefrontService.confirmRefund(request);
        return Map.of("code", "SUCCESS", "message", "成功");
    }

    @PostMapping("/payments/wechat/config-check")
    public ApiResponse<Map<String, Object>> configCheck() {
        boolean configured = !wechatPayProperties.getAppId().isBlank()
                && !wechatPayProperties.getMchId().isBlank()
                && !wechatPayProperties.getApiV3Key().isBlank()
                && wechatPayClient.isPaymentConfigured();
        return ApiResponse.ok(Map.of(
                "developmentMode", wechatPayProperties.isDevelopmentMode(),
                "configured", configured,
                "callbackVerificationConfigured", wechatPayClient.isCallbackVerificationConfigured(),
                "notifyUrl", wechatPayProperties.getNotifyUrl(),
                "refundNotifyUrl", wechatPayProperties.getRefundNotifyUrl()
        ));
    }
}
