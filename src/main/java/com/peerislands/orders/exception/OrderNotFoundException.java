package com.peerislands.orders.exception;

import java.util.UUID;

/** Maps to HTTP 404. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID id) {
        super("Order not found: " + id);
    }
}
