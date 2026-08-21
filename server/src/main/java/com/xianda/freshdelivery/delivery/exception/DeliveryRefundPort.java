package com.xianda.freshdelivery.delivery.exception;

public interface DeliveryRefundPort {
    RefundResult requestFullRefund(long orderId, String exceptionNo, String note);

    record RefundResult(long refundId, String refundNo, String status) {
    }
}
