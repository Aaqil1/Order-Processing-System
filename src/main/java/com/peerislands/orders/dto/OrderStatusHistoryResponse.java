package com.peerislands.orders.dto;

import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.domain.OrderStatusHistory;

import java.time.OffsetDateTime;

public record OrderStatusHistoryResponse(
        OrderStatus fromStatus,
        OrderStatus toStatus,
        OffsetDateTime changedAt
) {
    public static OrderStatusHistoryResponse from(OrderStatusHistory history) {
        return new OrderStatusHistoryResponse(
                history.getFromStatus(),
                history.getToStatus(),
                history.getChangedAt()
        );
    }
}
