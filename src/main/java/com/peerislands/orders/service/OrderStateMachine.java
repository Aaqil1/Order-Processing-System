package com.peerislands.orders.service;

import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.peerislands.orders.domain.OrderStatus.CANCELLED;
import static com.peerislands.orders.domain.OrderStatus.DELIVERED;
import static com.peerislands.orders.domain.OrderStatus.PENDING;
import static com.peerislands.orders.domain.OrderStatus.PROCESSING;
import static com.peerislands.orders.domain.OrderStatus.SHIPPED;

/**
 * Legal order status transitions, kept in one place so adding a status or an
 * edge is a one-line change rather than a hunt through scattered if-checks.
 */
@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, Set.of(PROCESSING, CANCELLED),
            PROCESSING, Set.of(SHIPPED),
            SHIPPED, Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of()
    );

    public boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Throws {@link InvalidStatusTransitionException} (-> 409) if the transition is illegal. */
    public void assertCanTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidStatusTransitionException(from, to);
        }
    }
}
