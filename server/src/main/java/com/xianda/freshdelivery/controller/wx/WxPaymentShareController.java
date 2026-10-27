package com.xianda.freshdelivery.controller.wx;

import com.xianda.freshdelivery.common.ApiResponse;
import com.xianda.freshdelivery.dto.PaymentShareDto;
import com.xianda.freshdelivery.dto.SharedPaymentDto;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代付摘要读取与实际付款分离：摘要接口公开但仅暴露金额，付款接口仍受微信登录保护。
 */
@RestController
public class WxPaymentShareController {
    private final StorefrontService storefrontService;
    private final WechatPaymentService wechatPaymentService;

    public WxPaymentShareController(StorefrontService storefrontService, WechatPaymentService wechatPaymentService) {
        this.storefrontService = storefrontService;
        this.wechatPaymentService = wechatPaymentService;
    }

    @GetMapping("/api/public/payment-shares/{token}")
    public ApiResponse<PaymentShareDto> paymentShare(@PathVariable String token) {
        return ApiResponse.ok(storefrontService.paymentShare(token));
    }

    @PostMapping("/api/wx/payment-shares/{token}/pay")
    public ApiResponse<SharedPaymentDto> pay(@PathVariable String token) {
        return ApiResponse.ok(wechatPaymentService.createSharedPayment(token));
    }
}
