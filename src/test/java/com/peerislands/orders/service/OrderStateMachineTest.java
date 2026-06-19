package com.peerislands.orders.service;

import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static com.peerislands.orders.domain.OrderStatus.CANCELLED;
import static com.peerislands.orders.domain.OrderStatus.DELIVERED;
import static com.peerislands.orders.domain.OrderStatus.PENDING;
import static com.peerislands.orders.domain.OrderStatus.PROCESSING;
import static com.peerislands.orders.domain.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL = Map.of(
            PENDING, Set.of(PROCESSING, CANCELLED),
            PROCESSING, Set.of(SHIPPED),
            SHIPPED, Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of()
    );

    @ParameterizedTest
    @CsvSource({
            "PENDING,PROCESSING",
            "PENDING,CANCELLED",
            "PROCESSING,SHIPPED",
            "SHIPPED,DELIVERED"
    })
    @DisplayName("every legal transition is allowed")
    void legalTransitionsAllowed(OrderStatus from, OrderStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isTrue();
        assertDoesNotThrow(() -> stateMachine.assertCanTransition(from, to));
    }

    @Test
    @DisplayName("every transition not in the legal set is rejected")
    void illegalTransitionsRejected() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean legal = LEGAL.get(from).contains(to);
                if (!legal) {
                    assertThat(stateMachine.canTransition(from, to))
                            .as("%s -> %s should be illegal", from, to)
                            .isFalse();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"})
    @DisplayName("cancel (transition to CANCELLED) is rejected from any non-PENDING status")
    void cannotCancelFromNonPending(OrderStatus from) {
        assertThat(stateMachine.canTransition(from, CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("cannot skip straight from PENDING to SHIPPED")
    void cannotSkipToShipped() {
        assertThat(stateMachine.canTransition(PENDING, SHIPPED)).isFalse();
    }

    @Test
    @DisplayName("cannot move a DELIVERED order anywhere")
    void deliveredIsTerminal() {
        for (OrderStatus to : OrderStatus.values()) {
            assertThat(stateMachine.canTransition(DELIVERED, to)).isFalse();
        }
    }

    @Test
    @DisplayName("assertCanTransition throws 409-mapped exception on illegal transition")
    void assertThrowsOnIllegal() {
        assertThatThrownBy(() -> stateMachine.assertCanTransition(SHIPPED, CANCELLED))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }
}
