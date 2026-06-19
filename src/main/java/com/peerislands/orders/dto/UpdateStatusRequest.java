package com.peerislands.orders.dto;

import com.peerislands.orders.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
        @NotNull(message = "status is required")
        OrderStatus status
) {
}
