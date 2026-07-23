package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
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

    public OrderDetailDto confirmDevelopmentPayment(Long orderId) {
        if (!wechatPayClient.isDevelopmentMode()) {
            throw new BusinessException(403, "生产模式不允许使用开发支付确认接口");
        }
        OrderDetailDto order = storefrontService.confirmDevelopmentPayment(orderId);
        enqueuePrint(order);
        return order;
    }

    public OrderDetailDto confirmPayment(PaymentNotifyRequest request) {
        OrderDetailDto order = storefrontService.confirmPayment(request);
        enqueuePrint(order);
        return order;
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
        if (wechatPayClient.isDevelopmentMode()) {
            return storefrontService.approveRefund(refundId);
        }
        wechatPayClient.requestRefund(refund, order);
        return storefrontService.markRefundProcessing(refundId);
    }

    public RefundDto createAdminRefund(AdminRefundCreateRequest request) {
        RefundDto refund = storefrontService.createAdminRefund(request);
        return approveRefund(refund.id());
    }
}
