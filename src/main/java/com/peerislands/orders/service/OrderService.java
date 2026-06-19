package com.peerislands.orders.service;

import com.peerislands.orders.domain.IdempotencyKey;
import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderItem;
import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.domain.OrderStatusHistory;
import com.peerislands.orders.dto.CreateOrderRequest;
import com.peerislands.orders.dto.OrderResponse;
import com.peerislands.orders.dto.PageResponse;
import com.peerislands.orders.exception.InvalidStatusTransitionException;
import com.peerislands.orders.exception.OrderNotFoundException;
import com.peerislands.orders.repository.IdempotencyKeyRepository;
import com.peerislands.orders.repository.OrderRepository;
import com.peerislands.orders.repository.OrderStatusHistoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OrderStateMachine stateMachine;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository historyRepository,
                        IdempotencyKeyRepository idempotencyKeyRepository,
                        OrderStateMachine stateMachine) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.stateMachine = stateMachine;
    }

    // Persists the order and its items in one transaction. If an Idempotency-Key
    // is supplied and already seen, returns the original order instead of a dupe.
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
        boolean hasKey = StringUtils.hasText(idempotencyKey);
        if (hasKey) {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByIdemKey(idempotencyKey);
            if (existing.isPresent()) {
                return detailById(existing.get().getOrderId());
            }
        }

        Order order = buildOrder(request);

        try {
            orderRepository.save(order);
            recordHistory(order.getId(), null, OrderStatus.PENDING);
            if (hasKey) {
                idempotencyKeyRepository.save(new IdempotencyKey(UUID.randomUUID(), idempotencyKey, order.getId()));
            }
        } catch (DataIntegrityViolationException e) {
            // Concurrent request won the race on the same idempotency key.
            if (hasKey) {
                return idempotencyKeyRepository.findByIdemKey(idempotencyKey)
                        .map(k -> detailById(k.getOrderId()))
                        .orElseThrow(() -> e);
            }
            throw e;
        }

        return detailById(order.getId());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID id) {
        return detailById(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listOrders(OrderStatus status, Pageable pageable) {
        var page = (status == null)
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        return PageResponse.from(page, OrderResponse::summary);
    }

    // Validates the change against the state machine. A concurrent write (e.g. the
    // scheduler touching the same row) trips the @Version lock and surfaces as 409.
    @Transactional
    public OrderResponse updateStatus(UUID id, OrderStatus target) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        OrderStatus current = order.getStatus();
        stateMachine.assertCanTransition(current, target);
        order.setStatus(target);   // flushed on commit by dirty checking
        recordHistory(id, current, target);
        return detailById(id);
    }

    // Cancels via an atomic conditional update; 0 rows means it's no longer PENDING.
    @Transactional
    public OrderResponse cancel(UUID id) {
        int updated = orderRepository.cancelIfPending(id);
        if (updated == 0) {
            // Distinguish "doesn't exist" (404) from "not cancellable" (409).
            Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
            throw new InvalidStatusTransitionException(
                    "Order " + id + " cannot be cancelled from status " + order.getStatus());
        }
        recordHistory(id, OrderStatus.PENDING, OrderStatus.CANCELLED);
        return detailById(id);
    }

    private Order buildOrder(CreateOrderRequest request) {
        BigDecimal total = request.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Order order = new Order(UUID.randomUUID(), request.customerId(), OrderStatus.PENDING, total);
        for (var itemReq : request.items()) {
            order.addItem(new OrderItem(UUID.randomUUID(), itemReq.productId(), itemReq.quantity(), itemReq.unitPrice()));
        }
        return order;
    }

    private void recordHistory(UUID orderId, OrderStatus from, OrderStatus to) {
        historyRepository.save(new OrderStatusHistory(UUID.randomUUID(), orderId, from, to));
    }

    private OrderResponse detailById(UUID id) {
        Order order = orderRepository.findByIdWithItems(id).orElseThrow(() -> new OrderNotFoundException(id));
        List<OrderStatusHistory> history = historyRepository.findByOrderIdOrderByChangedAtAsc(id);
        return OrderResponse.detail(order, history);
    }
}
