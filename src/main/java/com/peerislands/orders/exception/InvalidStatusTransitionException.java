package com.peerislands.orders.exception;

import com.peerislands.orders.domain.OrderStatus;

/** Maps to HTTP 409. Thrown when a requested status change violates the state machine. */
public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Illegal status transition: " + from + " -> " + to);
    }

    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}
