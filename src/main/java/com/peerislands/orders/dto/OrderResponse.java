package com.peerislands.orders.dto;

import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderStatusHistory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID customerId,
        String status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<OrderItemResponse> items,
        List<OrderStatusHistoryResponse> history
) {

    /** Summary projection (no items/history) — used by the list endpoint. */
    public static OrderResponse summary(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                null,
                null
        );
    }

    /** Full projection including items and status history. */
    public static OrderResponse detail(Order order, List<OrderStatusHistory> history) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                history.stream().map(OrderStatusHistoryResponse::from).toList()
        );
    }
}
