package com.xianda.freshdelivery.service;

import com.xianda.freshdelivery.common.BusinessException;
import com.xianda.freshdelivery.common.CurrentUserContext;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.BatchOrderActionResult;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.PaymentConfirmationResult;
import com.xianda.freshdelivery.dto.PaymentDto;
import com.xianda.freshdelivery.dto.PaymentNotifyRequest;
import com.xianda.freshdelivery.dto.PaymentShareDto;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.dto.RefundNotifyRequest;
import com.xianda.freshdelivery.dto.SharedPaymentDto;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class WechatPaymentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WechatPaymentService.class);
    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Shanghai");
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

    public synchronized PaymentDto createPayment(Long orderId) {
        OrderDetailDto order = storefrontService.preparePayment(orderId);
        if (wechatPayClient.isPaymentConfigured()) {
            wechatPayClient.closePayment(order);
            order = storefrontService.renewPaymentAttempt(orderId);
        }
        String openId = authService.openIdForUser(CurrentUserContext.userId());
        return wechatPayClient.createJsapiPayment(order, openId);
    }

    public synchronized PaymentDto changeToSelfPayment(Long orderId) {
        OrderDetailDto currentOrder = storefrontService.preparePendingPayment(orderId);
        closeActivePayment(currentOrder);
        OrderDetailDto order = storefrontService.activateSelfPayment(orderId);
        String openId = authService.openIdForUser(CurrentUserContext.userId());
        return wechatPayClient.createJsapiPayment(order, openId);
    }

    public synchronized PaymentShareDto createPaymentShare(Long orderId) {
        OrderDetailDto order = storefrontService.preparePendingPayment(orderId);
        closeActivePayment(order);
        return storefrontService.createPaymentShare(orderId);
    }

    public synchronized SharedPaymentDto createSharedPayment(String token) {
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

    public synchronized OrderDetailDto confirmPayment(PaymentNotifyRequest request) {
        validatePaymentNotificationIdentity(request);
        PaymentConfirmationResult result = storefrontService.confirmPayment(request);
        if (result.requiresRefundSubmission()) {
            submitRefund(result.refundId());
            return storefrontService.adminOrder(result.order().id());
        }
        if (result.newlyPaid()) {
            enqueuePrint(result.order());
        }
        return result.order();
    }

    public synchronized OrderDetailDto refreshPaymentStatus(Long orderId) {
        OrderDetailDto order = storefrontService.order(orderId);
        boolean unresolvedPayment = "待支付".equals(order.status())
                || (order.paidAmount() == 0 && List.of("已关闭", "已取消").contains(order.status()));
        if (!unresolvedPayment) {
            return order;
        }
        PaymentNotifyRequest payment = wechatPayClient.queryPayment(order);
        if (!"SUCCESS".equalsIgnoreCase(payment.tradeState())) {
            return order;
        }
        return confirmPayment(payment);
    }

    public synchronized OrderDetailDto cancelOrder(Long orderId, boolean returnToCart) {
        OrderDetailDto order = storefrontService.preparePendingPayment(orderId);
        closeActivePayment(order);
        return storefrontService.cancelOrder(orderId, returnToCart);
    }

    public synchronized OrderDetailDto cancelAdminOrder(Long orderId) {
        OrderDetailDto order = storefrontService.adminOrder(orderId);
        if (order.paidAmount() != null && order.paidAmount() > 0) {
            throw new BusinessException(409, "已付款订单不能直接取消，请通过退款流程处理");
        }
        if ("待支付".equals(order.status())) {
            closeActivePayment(order);
        }
        return storefrontService.adminCancelOrder(orderId);
    }

    private void closeActivePayment(OrderDetailDto order) {
        if (wechatPayClient.isPaymentConfigured()) {
            wechatPayClient.closePayment(order);
        }
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
        return submitRefund(refundId);
    }

    public RefundDto createAdminRefund(AdminRefundCreateRequest request) {
        RefundDto refund = storefrontService.createAdminRefund(request);
        return submitRefund(refund.id());
    }

    public RefundDto retryRefund(Long refundId) {
        RefundDto refund = storefrontService.adminRefund(refundId);
        if (storefrontService.requiresManualRefundReconcile(refund)) {
            throw new BusinessException(409, "该退款的微信状态未确认，请先同步微信状态，不能直接重新发起");
        }
        return submitRefund(refundId);
    }

    public RefundDto reconcileRefund(Long refundId) {
        return reconcileRefund(storefrontService.adminRefund(refundId));
    }

    public RefundDto confirmRefund(RefundNotifyRequest request) {
        return storefrontService.confirmRefund(request);
    }

    @Scheduled(
            fixedDelayString = "${wechat.pay.refund-retry-scan-ms:60000}",
            initialDelayString = "${wechat.pay.refund-retry-initial-delay-ms:30000}"
    )
    public void retryAndReconcileRefunds() {
        LocalDateTime now = LocalDateTime.now(STORE_ZONE);
        if (!wechatPayClient.isRefundConfigured()) {
            for (RefundDto refund : storefrontService.processingRefunds()) {
                if (isProcessingCheckDue(refund, now)) {
                    storefrontService.markRefundFailed(
                            refund.id(),
                            "REFUND_CONFIG_UNAVAILABLE",
                            "微信退款配置不可用，已转为可重试失败"
                    );
                }
            }
            return;
        }
        for (RefundDto refund : storefrontService.retryableRefunds()) {
            if (isRetryDue(refund, now)) {
                submitRefund(refund.id());
            }
        }
        for (RefundDto refund : storefrontService.processingRefunds()) {
            if (isProcessingCheckDue(refund, now)) {
                reconcileRefund(refund);
            }
        }
    }

    private RefundDto submitRefund(Long refundId) {
        RefundDto current = storefrontService.adminRefund(refundId);
        if ("退款成功".equals(current.status())) {
            return current;
        }
        RefundDto submitting = storefrontService.markRefundSubmitting(refundId);
        OrderDetailDto order = storefrontService.adminOrder(submitting.orderId());
        try {
            RefundNotifyRequest result = wechatPayClient.requestRefund(submitting, order);
            return storefrontService.confirmRefund(result);
        } catch (BusinessException exception) {
            if (isRefundRequestMismatch(exception)) {
                return reconcileMismatchRefund(submitting, exception);
            }
            return storefrontService.markRefundFailed(
                    refundId,
                    refundFailureCode(exception),
                    exception.getMessage()
            );
        } catch (RuntimeException exception) {
            LOGGER.error("Wechat refund submission failed, refundId={}", refundId, exception);
            return storefrontService.markRefundFailed(
                    refundId,
                    "CLIENT_EXCEPTION",
                    exception.getMessage()
            );
        }
    }

    private RefundDto reconcileRefund(RefundDto refund) {
        try {
            RefundNotifyRequest result = wechatPayClient.queryRefund(refund);
            return storefrontService.confirmRefund(result);
        } catch (BusinessException exception) {
            return storefrontService.markRefundFailed(
                    refund.id(),
                    "QUERY_HTTP_" + exception.code(),
                    exception.getMessage()
            );
        } catch (RuntimeException exception) {
            LOGGER.error("Wechat refund reconciliation failed, refundId={}", refund.id(), exception);
            return storefrontService.markRefundFailed(
                    refund.id(),
                    "QUERY_EXCEPTION",
                    exception.getMessage()
            );
        }
    }

    private RefundDto reconcileMismatchRefund(RefundDto refund, BusinessException submissionException) {
        try {
            RefundNotifyRequest result = wechatPayClient.queryRefund(refund);
            return storefrontService.confirmRefund(result);
        } catch (RuntimeException queryException) {
            return storefrontService.markRefundFailed(
                    refund.id(),
                    "REFUND_STATUS_UNCONFIRMED",
                    "微信拒绝本次退款请求且无法确认原退款状态；请先同步微信状态或在商户平台按退款单号核对。提交结果："
                            + submissionException.getMessage()
                            + "；查询结果："
                            + queryException.getMessage()
            );
        }
    }

    private boolean isRetryDue(RefundDto refund, LocalDateTime now) {
        LocalDateTime lastAttempt = parseDateTime(refund.lastAttemptAt());
        if (lastAttempt == null) {
            return true;
        }
        int exponent = Math.min(Math.max(refund.retryCount(), 1), 6);
        long delayMinutes = 1L << exponent;
        return !lastAttempt.plusMinutes(delayMinutes).isAfter(now);
    }

    private boolean isProcessingCheckDue(RefundDto refund, LocalDateTime now) {
        LocalDateTime lastAttempt = parseDateTime(refund.lastAttemptAt());
        return lastAttempt == null || !lastAttempt.plusMinutes(2).isAfter(now);
    }

    private LocalDateTime parseDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String refundFailureCode(BusinessException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toUpperCase();
        if (message.contains("订单金额或退款金额与之前请求不一致".toUpperCase())) {
            return "REFUND_REQUEST_MISMATCH";
        }
        if (message.contains("ABNORMAL")) {
            return "ABNORMAL";
        }
        if (message.contains("CLOSED")) {
            return "CLOSED";
        }
        return "HTTP_" + exception.code();
    }

    private boolean isRefundRequestMismatch(BusinessException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        return message.contains("订单金额或退款金额与之前请求不一致");
    }
}
