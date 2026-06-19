package com.peerislands.orders.scheduler;

import com.peerislands.orders.AbstractIntegrationTest;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderPromotionSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OrderPromotionScheduler scheduler;

    @Autowired
    OrderRepository orderRepository;

    @Test
    void promotesPendingOrdersToProcessing() {
        Order pending = new Order(UUID.randomUUID(), UUID.randomUUID(), OrderStatus.PENDING, new BigDecimal("12.00"));
        orderRepository.save(pending);

        scheduler.promotePendingOrders();

        Order reloaded = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    void doesNotTouchNonPendingOrders() {
        Order shipped = new Order(UUID.randomUUID(), UUID.randomUUID(), OrderStatus.SHIPPED, new BigDecimal("12.00"));
        orderRepository.save(shipped);

        scheduler.promotePendingOrders();

        Order reloaded = orderRepository.findById(shipped.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }
}
