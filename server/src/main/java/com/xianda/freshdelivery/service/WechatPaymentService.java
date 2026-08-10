package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.PaymentConfirmationResult;
import com.xianda.freshdelivery.dto.PaymentDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.SharedPaymentDto;
import com.xianda.freshdelivery.dto.BatchOrderActionResult;
import java.util.ArrayList;
import java.util.List;
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
        if (wechatPayClient.isPaymentConfigured()) {
            wechatPayClient.closePayment(order);
            order = storefrontService.renewPaymentAttempt(orderId);
        }
        String openId = authService.openIdForUser(CurrentUserContext.userId());
        return wechatPayClient.createJsapiPayment(order, openId);
    }

    public SharedPaymentDto createSharedPayment(String token) {
        StorefrontService.PaymentSharePaymentContext context = storefrontService.preparePaymentShare(token);
        Long payerUserId = CurrentUserContext.userId();
        if (payerUserId.equals(context.creatorUserId())) {
            throw new BusinessException(409, "请使用微信支付完成本人的订单付款");
        }
        OrderDetailDto order = context.order();
        if (wechatPayClient.isPaymentConfigured()) {
            wechatPayClient.closePayment(order);
            order = storefrontService.renewPaymentAttemptForShare(token);
        }
        String openId = authService.openIdForUser(payerUserId);
        PaymentDto payment = wechatPayClient.createJsapiPayment(order, openId);
        return new SharedPaymentDto(
                payment.timeStamp(),
                payment.nonceStr(),
                payment.packageValue(),
                payment.signType(),
                payment.paySign()
        );
    }

    public OrderDetailDto confirmPayment(PaymentNotifyRequest request) {
        validatePaymentNotificationIdentity(request);
        PaymentConfirmationResult result = storefrontService.confirmPayment(request);
        if (result.newlyPaid()) {
            enqueuePrint(result.order());
        }
        return result.order();
    }

    public OrderDetailDto refreshPaymentStatus(Long orderId) {
        OrderDetailDto order = storefrontService.order(orderId);
        if (!"待支付".equals(order.status())) {
            return order;
        }
        PaymentNotifyRequest payment = wechatPayClient.queryPayment(order);
        if (!"SUCCESS".equalsIgnoreCase(payment.tradeState())) {
            return order;
        }
        return confirmPayment(payment);
    }

    public OrderDetailDto cancelOrder(Long orderId, boolean returnToCart) {
        OrderDetailDto order = storefrontService.preparePayment(orderId);
        if (wechatPayClient.isPaymentConfigured()) {
            wechatPayClient.closePayment(order);
        }
        return storefrontService.cancelOrder(orderId, returnToCart);
    }

    public OrderDetailDto deliverAdminOrder(Long orderId) {
        ensurePaymentTransaction(orderId);
        return storefrontService.deliverOrder(orderId);
    }

    public OrderDetailDto refreshAdminPaymentTransaction(Long orderId) {
        return ensurePaymentTransaction(orderId);
    }

    public BatchOrderActionResult batchDeliverAdminOrders(List<Long> orderIds) {
        List<Long> uniqueIds = orderIds == null ? List.of() : orderIds.stream().distinct().toList();
        List<Long> processed = new ArrayList<>();
        List<BatchOrderActionResult.BatchOrderActionError> errors = new ArrayList<>();
        for (Long orderId : uniqueIds) {
            try {
                OrderDetailDto order = ensurePaymentTransaction(orderId);
                storefrontService.deliverOrder(orderId);
                processed.add(orderId);
            } catch (BusinessException exception) {
                String orderNo;
                try {
                    orderNo = storefrontService.adminOrder(orderId).orderNo();
                } catch (Exception ignored) {
                    orderNo = "";
                }
                errors.add(new BatchOrderActionResult.BatchOrderActionError(orderId, orderNo, exception.getMessage()));
            }
        }
        return new BatchOrderActionResult(uniqueIds.size(), processed.size(), errors.size(), processed, errors);
    }

    private OrderDetailDto ensurePaymentTransaction(Long orderId) {
        OrderDetailDto order = storefrontService.adminOrder(orderId);
        if (hasText(order.transactionId())) {
            return order;
        }
        PaymentNotifyRequest payment = wechatPayClient.queryPayment(order);
        if (!"SUCCESS".equalsIgnoreCase(payment.tradeState())) {
            throw new BusinessException(409, "微信支付尚未成功，无法生成发货表格");
        }
        return storefrontService.recordPaymentTransaction(orderId, payment.transactionId());
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
