package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.PaymentConfirmationResult;
import com.xianda.freshdelivery.dto.PaymentDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.RefundDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WechatPaymentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WechatPaymentService.class);
    private final AuthService authService;
    private final StorefrontService storefrontService;
    private final WechatPayClient wechatPayClient;
    private final PrintJobService printJobService;

    public WechatPaymentService(AuthService authService, StorefrontService storefrontService, WechatPayClient wechatPayClient, PrintJobService printJobService) {
        this.authService = authService;
        this.storefrontService = storefrontService;
        this.wechatPayClient = wechatPayClient;
        this.printJobService = printJobService;
    }

    public PaymentDto createPayment(Long orderId) {
        OrderDetailDto order = storefrontService.preparePayment(orderId);
        String openId = authService.openIdForUser(CurrentUserContext.userId());
        return wechatPayClient.createJsapiPayment(order, openId);
    }

    public OrderDetailDto confirmPayment(PaymentNotifyRequest request) {
        validatePaymentNotificationIdentity(request);
        PaymentConfirmationResult result = storefrontService.confirmPayment(request);
        if (result.newlyPaid()) {
            enqueuePrint(result.order());
        }
        return result.order();
    }

    private void validatePaymentNotificationIdentity(PaymentNotifyRequest request) {
        if (!hasText(request.appId()) || !hasText(request.mchId()) || request.totalAmount() == null) {
            throw new BusinessException(401, "微信支付生产回调缺少商户身份或金额信息");
        }
        if (hasText(request.appId()) && !request.appId().equals(wechatPayClient.appId())) {
            throw new BusinessException(401, "微信支付回调 AppID 不匹配");
        }
        if (hasText(request.mchId()) && !request.mchId().equals(wechatPayClient.mchId())) {
            throw new BusinessException(401, "微信支付回调商户号不匹配");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void enqueuePrint(OrderDetailDto order) {
        try {
            printJobService.enqueuePaidOrder(order);
        } catch (RuntimeException exception) {
            LOGGER.error("Payment confirmed but receipt job creation failed, orderId={}", order.id(), exception);
        }
    }

    public RefundDto approveRefund(Long refundId) {
        RefundDto refund = storefrontService.adminRefund(refundId);
        OrderDetailDto order = storefrontService.adminOrder(refund.orderId());
        wechatPayClient.requestRefund(refund, order);
        return storefrontService.markRefundProcessing(refundId);
    }

    public RefundDto createAdminRefund(AdminRefundCreateRequest request) {
        RefundDto refund = storefrontService.createAdminRefund(request);
        return approveRefund(refund.id());
    }
}
