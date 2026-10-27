package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.service.StorefrontService;
import com.xianda.freshdelivery.service.WechatPaymentService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StorefrontOrderBridge implements OrderBridgePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorefrontOrderBridge.class);

    private final StorefrontService storefrontService;
    private final WechatPaymentService wechatPaymentService;

    public StorefrontOrderBridge(StorefrontService storefrontService, WechatPaymentService wechatPaymentService) {
        this.storefrontService = storefrontService;
        this.wechatPaymentService = wechatPaymentService;
    }

    @Override
    public Optional<OrderDetailDto> loadOrder(long orderId) {
        try {
            return Optional.ofNullable(storefrontService.adminOrder(orderId));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void markDelivering(long orderId) {
        OrderDetailDto order = loadOrder(orderId).orElse(null);
        if (order == null) {
            LOGGER.warn("订单同步失败：订单 {} 不存在，无法置为配送中", orderId);
            throw new OrderBridgeGapException(orderId, "不存在");
        }
        if (STATUS_DELIVERING.equals(order.status()) || STATUS_COMPLETED.equals(order.status())) {
            return;
        }
        wechatPaymentService.deliverAdminOrder(orderId);
    }

    @Override
    public void markCompleted(long orderId) {
        OrderDetailDto order = loadOrder(orderId).orElse(null);
        if (order == null) {
            LOGGER.warn("订单同步失败：订单 {} 不存在，无法置为已完成", orderId);
            throw new OrderBridgeGapException(orderId, "不存在");
        }
        if (STATUS_COMPLETED.equals(order.status())) {
            return;
        }
        if (!STATUS_DELIVERING.equals(order.status())) {
            wechatPaymentService.deliverAdminOrder(orderId);
        }
        storefrontService.completeOrder(orderId);
    }

    @Override
    public void restoreToPreparing(long orderId) {
        OrderDetailDto order = loadOrder(orderId).orElse(null);
        if (order == null) {
            LOGGER.warn("订单同步失败：订单 {} 不存在，无法回到备货中", orderId);
            throw new OrderBridgeGapException(orderId, "不存在");
        }
        if (STATUS_PREPARING.equals(order.status())) {
            return;
        }
        if ("已支付/待接单".equals(order.status())) {
            storefrontService.prepareOrder(orderId);
            return;
        }
        LOGGER.warn(
                "订单同步缺口：订单 {} 当前状态[{}]，StorefrontService 没有公开方法可将其退回[备货中]，需人工处理",
                orderId,
                order.status()
        );
        throw new OrderBridgeGapException(orderId, order.status());
    }

    public static class OrderBridgeGapException extends RuntimeException {
        private final long orderId;
        private final String orderStatus;

        public OrderBridgeGapException(long orderId, String orderStatus) {
            super("订单 " + orderId + " 当前状态[" + orderStatus + "]无法回退到备货中");
            this.orderId = orderId;
            this.orderStatus = orderStatus;
        }

        public long orderId() {
            return orderId;
        }

        public String orderStatus() {
            return orderStatus;
        }
    }
}
