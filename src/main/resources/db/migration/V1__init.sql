-- Initial schema for the Order Processing System.
-- Schema is owned by Flyway (versioned), never by Hibernate ddl-auto.

CREATE TABLE orders (
    id           UUID PRIMARY KEY,
    customer_id  UUID NOT NULL,
    status       VARCHAR(20) NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL,
    version      BIGINT NOT NULL DEFAULT 0,          -- optimistic locking
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id         UUID PRIMARY KEY,
    order_id   UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL,
    quantity   INT NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12,2) NOT NULL
);

CREATE TABLE order_status_history (
    id          UUID PRIMARY KEY,
    order_id    UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency keys for create-order de-duplication (retried / double-clicked POST).
CREATE TABLE idempotency_keys (
    id           UUID PRIMARY KEY,
    idem_key     VARCHAR(255) NOT NULL UNIQUE,
    order_id     UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_status   ON orders(status);        -- the list filter
CREATE INDEX idx_orders_customer ON orders(customer_id);   -- a customer's orders
CREATE INDEX idx_items_order     ON order_items(order_id);
CREATE INDEX idx_history_order   ON order_status_history(order_id);
