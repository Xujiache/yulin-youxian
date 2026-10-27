package com.xianda.freshdelivery.delivery.exception;

import com.xianda.freshdelivery.delivery.common.DeliveryException;
import com.xianda.freshdelivery.delivery.task.OrderBridgePort;
import com.xianda.freshdelivery.dto.AdminRefundCreateRequest;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.RefundDto;
import com.xianda.freshdelivery.service.WechatPaymentService;
import org.springframework.stereotype.Component;

@Component
public class WechatDeliveryRefundPort implements DeliveryRefundPort {
    private final OrderBridgePort orderBridgePort;
    private final WechatPaymentService paymentService;

    public WechatDeliveryRefundPort(OrderBridgePort orderBridgePort, WechatPaymentService paymentService) {
        this.orderBridgePort = orderBridgePort;
        this.paymentService = paymentService;
    }

    @Override
    public RefundResult requestFullRefund(long orderId, String exceptionNo, String note) {
        OrderDetailDto order = orderBridgePort.loadOrder(orderId)
                .orElseThrow(() -> new DeliveryException(404, "退款订单不存在：" + orderId));
        String marker = "配送异常 " + exceptionNo;
        RefundDto existing = order.refunds() == null ? null : order.refunds().stream()
                .filter(refund -> refund.reason() != null && refund.reason().contains(marker))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return result(existing);
        }
        int paid = order.paidAmount() == null ? 0 : order.paidAmount();
        int refunded = order.refundedAmount() == null ? 0 : order.refundedAmount();
        int refundable = Math.max(paid - refunded, 0);
        if (refundable <= 0) {
            throw new DeliveryException(409, "订单没有可退金额：" + orderId);
        }
        if (order.userId() == null) {
            throw new DeliveryException(409, "订单缺少付款用户，无法退款：" + orderId);
        }
        String reason = marker + (note == null || note.isBlank() ? "" : "：" + note.trim());
        RefundDto refund = paymentService.createAdminRefund(
                new AdminRefundCreateRequest(order.userId(), orderId, refundable, reason));
        return result(refund);
    }

    private static RefundResult result(RefundDto refund) {
        return new RefundResult(refund.id(), refund.refundNo(), refund.status());
    }
}
