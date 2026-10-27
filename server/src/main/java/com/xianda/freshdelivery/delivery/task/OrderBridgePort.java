package com.xianda.freshdelivery.delivery.task;

import com.xianda.freshdelivery.dto.OrderDetailDto;
import java.util.Optional;

public interface OrderBridgePort {
    String STATUS_PREPARING = "备货中";
    String STATUS_DELIVERING = "配送中";
    String STATUS_COMPLETED = "已完成";

    Optional<OrderDetailDto> loadOrder(long orderId);

    void markDelivering(long orderId);

    void markCompleted(long orderId);

    void restoreToPreparing(long orderId);
}
