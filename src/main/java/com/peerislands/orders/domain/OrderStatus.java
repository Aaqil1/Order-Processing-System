package com.peerislands.orders.domain;

/**
 * The lifecycle states of an order. These are not free-form labels; the legal
 * transitions between them are enforced centrally by
 * {@link com.peerislands.orders.service.OrderStateMachine}.
 */
public enum OrderStatus {
    PENDING,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
