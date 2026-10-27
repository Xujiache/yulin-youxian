package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.dto.AddressDto;
import com.xianda.freshdelivery.dto.OrderDetailDto;
import com.xianda.freshdelivery.dto.OrderItemDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class FakeOrderBridgePort implements OrderBridgePort {
    private final Map<Long, OrderDetailDto> orders = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private boolean restoreFails;
    private boolean completeFailsOnce;

    void putOrder(long orderId, String orderNo, String status) {
        orders.put(orderId, new OrderDetailDto(
                orderId,
                orderNo,
                status,
                new AddressDto(1L, "王女士", "13800005678", "3号楼2单元1201室", "阳光小区", 30.12, 120.76, true),
                "今日 14:00-16:00",
                List.of(new OrderItemDto(1L, 11L, "西红柿", "", "斤", 500, new BigDecimal("2"), 1000)),
                1000, 300, 0, 1300, 1300, 0,
                "放门口，不用敲门",
                // 下单时间决定任务的 delivery_date，写死日期会让波次号/统计断言在次日失败
                TaskTimes.today().atTime(10, 30, 15).format(TaskTimes.ISO_SECONDS),
                null, null, 9L, List.of(), "TX001"
        ));
    }

    void setStatus(long orderId, String status) {
        OrderDetailDto order = orders.get(orderId);
        if (order == null) {
            return;
        }
        orders.put(orderId, new OrderDetailDto(
                order.id(), order.orderNo(), status, order.address(), order.deliverySlot(), order.items(),
                order.productAmount(), order.deliveryFee(), order.packageFee(), order.payableAmount(),
                order.paidAmount(), order.refundedAmount(), order.remark(), order.createdAt(),
                order.latestRefundStatus(), order.latestRefundReason(), order.userId(), order.refunds(),
                order.transactionId()
        ));
    }

    void failRestore() {
        this.restoreFails = true;
    }

    void failCompleteOnce() {
        this.completeFailsOnce = true;
    }

    String status(long orderId) {
        OrderDetailDto order = orders.get(orderId);
        return order == null ? null : order.status();
    }

    List<String> calls() {
        return calls;
    }

    @Override
    public Optional<OrderDetailDto> loadOrder(long orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public void markDelivering(long orderId) {
        calls.add("markDelivering:" + orderId);
        setStatus(orderId, STATUS_DELIVERING);
    }

    @Override
    public void markCompleted(long orderId) {
        calls.add("markCompleted:" + orderId);
        if (completeFailsOnce) {
            completeFailsOnce = false;
            throw new IllegalStateException("模拟订单桥提交后崩溃");
        }
        setStatus(orderId, STATUS_COMPLETED);
    }

    @Override
    public void restoreToPreparing(long orderId) {
        calls.add("restoreToPreparing:" + orderId);
        if (restoreFails) {
            throw new IllegalStateException("订单当前状态无法回退到备货中");
        }
        setStatus(orderId, STATUS_PREPARING);
    }
}
