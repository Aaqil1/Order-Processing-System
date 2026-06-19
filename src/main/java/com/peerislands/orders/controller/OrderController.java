package com.peerislands.orders.controller;

import com.peerislands.orders.domain.OrderStatus;
import com.peerislands.orders.dto.CreateOrderRequest;
import com.peerislands.orders.dto.OrderResponse;
import com.peerislands.orders.dto.PageResponse;
import com.peerislands.orders.dto.UpdateStatusRequest;
import com.peerislands.orders.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Create, retrieve, list, update status, and cancel orders")
public class OrderController {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Create an order with one or more items")
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            UriComponentsBuilder uriBuilder) {
        OrderResponse created = orderService.createOrder(request, idempotencyKey);
        URI location = uriBuilder.path("/api/v1/orders/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Retrieve an order with its items and status history")
    public OrderResponse get(@PathVariable UUID id) {
        return orderService.getOrder(id);
    }

    @GetMapping
    @Operation(summary = "List orders, optionally filtered by status (paginated)")
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderService.listOrders(status, pageable);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change an order's status (validated against the state machine)")
    public OrderResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return orderService.updateStatus(id, request.status());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order (allowed only from PENDING)")
    public OrderResponse cancel(@PathVariable UUID id) {
        return orderService.cancel(id);
    }
}
