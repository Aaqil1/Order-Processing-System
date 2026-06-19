package com.peerislands.orders.concurrency;

import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import com.peerislands.orders.repository.OrderRepository;
import com.peerislands.orders.scheduler.OrderPromotionScheduler;
import com.peerislands.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The differentiator test: a customer cancels at the same instant the scheduler
 * promotes the order. With the atomic conditional update
 * ({@code cancelIfPending}) exactly one side wins and the row is never left in a
 * corrupted or in-between state.
 *
 * <p>This test FAILS against the naive read-then-write implementation
 * ({@code findById} then {@code if (status == PENDING) save(CANCELLED)}), because
 * both threads can read PENDING and then clobber each other — which is precisely
 * the bug it is designed to catch.
 */
@SpringBootTest
class CancelVsPromoteConcurrencyTest extends AbstractIntegrationTest {

    private static final int ITERATIONS = 40;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderPromotionScheduler scheduler;

    @Autowired
    OrderRepository orderRepository;

    @Test
    void cancelAndPromoteRaceLeavesExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                UUID orderId = seedPendingOrder();
                CountDownLatch start = new CountDownLatch(1);
                AtomicBoolean cancelWon = new AtomicBoolean(false);

                Future<?> cancelTask = pool.submit(() -> {
                    awaitQuietly(start);
                    try {
                        orderService.cancel(orderId);
                        cancelWon.set(true);
                    } catch (InvalidStatusTransitionException expectedWhenLost) {
                        cancelWon.set(false);
                    }
                });

                Future<?> promoteTask = pool.submit(() -> {
                    awaitQuietly(start);
                    scheduler.promotePendingOrders();
                });

                start.countDown(); // fire both at once
                cancelTask.get(10, TimeUnit.SECONDS);
                promoteTask.get(10, TimeUnit.SECONDS);

                OrderStatus finalStatus = orderRepository.findById(orderId).orElseThrow().getStatus();

                // Never corrupted, never stuck in PENDING.
                assertThat(finalStatus)
                        .as("iteration %d final status", i)
                        .isIn(OrderStatus.CANCELLED, OrderStatus.PROCESSING);

                // The winner and the persisted state must agree — exactly one wins.
                if (cancelWon.get()) {
                    assertThat(finalStatus)
                            .as("iteration %d: cancel reported success", i)
                            .isEqualTo(OrderStatus.CANCELLED);
                } else {
                    assertThat(finalStatus)
                            .as("iteration %d: cancel reported conflict", i)
                            .isEqualTo(OrderStatus.PROCESSING);
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private UUID seedPendingOrder() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), OrderStatus.PENDING, new BigDecimal("10.00"));
        orderRepository.saveAndFlush(order);
        return order.getId();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
