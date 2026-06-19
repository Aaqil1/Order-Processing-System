package com.peerislands.orders.scheduler;

import com.peerislands.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderPromotionScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderPromotionScheduler.class);

    private final OrderRepository orderRepository;

    public OrderPromotionScheduler(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    // Promote all PENDING orders in one statement. On multiple instances you'd
    // guard this with a distributed lock (e.g. ShedLock) so it runs once per tick.
    @Scheduled(
            fixedRateString = "${orders.scheduler.promote-pending.fixed-rate-ms:300000}",
            initialDelayString = "${orders.scheduler.promote-pending.initial-delay-ms:0}")
    @Transactional
    public void promotePendingOrders() {
        int promoted = orderRepository.promotePending();
        if (promoted > 0) {
            log.info("Promoted {} order(s) PENDING -> PROCESSING", promoted);
        }
    }
}
