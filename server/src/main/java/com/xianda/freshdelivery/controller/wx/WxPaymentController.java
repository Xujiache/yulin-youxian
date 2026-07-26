package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.config.WechatPayProperties;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPayClient;
import com.xianda.freshdelivery.service.WechatPaymentService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wx")
public class WxPaymentController {
    private static final Logger LOGGER = LoggerFactory.getLogger(WxPaymentController.class);

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
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature) {
        PaymentNotifyRequest request = wechatPayClient.parsePaymentNotify(body, timestamp, nonce, serial, signature);
        wechatPaymentService.confirmPayment(request);
        return Map.of("code", "SUCCESS", "message", "成功");
    }

    @PostMapping("/refunds/wechat/notify")
    public Map<String, String> refundNotify(
            @RequestBody String body,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature) {
        RefundNotifyRequest request = wechatPayClient.parseRefundNotify(body, timestamp, nonce, serial, signature);
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
                "configured", configured,
                "callbackVerificationConfigured", wechatPayClient.isCallbackVerificationConfigured(),
                "notifyUrl", wechatPayProperties.getNotifyUrl(),
                "refundNotifyUrl", wechatPayProperties.getRefundNotifyUrl()
        ));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> paymentCallbackBusinessError(BusinessException exception) {
        int status = exception.code() >= 400 && exception.code() <= 599 ? exception.code() : 400;
        return ResponseEntity.status(HttpStatusCode.valueOf(status))
                .body(Map.of("code", "FAIL", "message", exception.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> paymentCallbackUnexpectedError(RuntimeException exception) {
        LOGGER.error("Wechat Pay callback failed unexpectedly", exception);
        return ResponseEntity.internalServerError()
                .body(Map.of("code", "FAIL", "message", "微信支付回调处理失败"));
    }
}
