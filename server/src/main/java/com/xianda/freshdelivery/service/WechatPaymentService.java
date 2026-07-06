package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentDto;
import com.xianda.freshdelivery.dto.RefundDto;
import org.springframework.stereotype.Service;

@Service
public class WechatPaymentService {
    private final AuthService authService;
    private final StorefrontService storefrontService;
    private final WechatPayClient wechatPayClient;

    public WechatPaymentService(AuthService authService, StorefrontService storefrontService, WechatPayClient wechatPayClient) {
        this.authService = authService;
        this.storefrontService = storefrontService;
        this.wechatPayClient = wechatPayClient;
    }

    public PaymentDto createPayment(Long orderId) {
        OrderDetailDto order = storefrontService.preparePayment(orderId);
        String openId = authService.openIdForUser(CurrentUserContext.userId());
        return wechatPayClient.createJsapiPayment(order, openId);
    }

    public OrderDetailDto confirmDevelopmentPayment(Long orderId) {
        if (!wechatPayClient.isDevelopmentMode()) {
            throw new BusinessException(403, "生产模式不允许使用开发支付确认接口");
        }
        return storefrontService.confirmDevelopmentPayment(orderId);
    }

    public RefundDto approveRefund(Long refundId) {
        RefundDto refund = storefrontService.adminRefund(refundId);
        OrderDetailDto order = storefrontService.adminOrder(refund.orderId());
        if (wechatPayClient.isDevelopmentMode()) {
            return storefrontService.approveRefund(refundId);
        }
        wechatPayClient.requestRefund(refund, order);
        return storefrontService.markRefundProcessing(refundId);
    }
}
