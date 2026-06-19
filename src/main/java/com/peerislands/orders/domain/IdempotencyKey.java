package com.peerislands.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps a client-supplied Idempotency-Key to the order it created. The UNIQUE
 * constraint on idem_key is what keeps de-duplication safe under concurrency.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    private UUID id;

    @Column(name = "idem_key", nullable = false, unique = true)
    private String idemKey;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKey() {
        // for JPA
    }

    public IdempotencyKey(UUID id, String idemKey, UUID orderId) {
        this.id = id;
        this.idemKey = idemKey;
        this.orderId = orderId;
    }

    public UUID getId() {
        return id;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
