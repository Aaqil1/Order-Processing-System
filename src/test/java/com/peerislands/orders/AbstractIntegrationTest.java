package com.peerislands.orders;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: a real PostgreSQL via Testcontainers, wired up with
 * {@code @ServiceConnection}. Uses the singleton-container pattern (started once,
 * reaped by Ryuk at JVM exit) so one container is shared across all test classes,
 * and resets the tables before each test.
 */
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void schedulerProperties(DynamicPropertyRegistry registry) {
        // Keep the scheduler bean present but never auto-firing; tests invoke it explicitly.
        registry.add("orders.scheduler.promote-pending.initial-delay-ms", () -> 3_600_000);
        registry.add("orders.scheduler.promote-pending.fixed-rate-ms", () -> 3_600_000);
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE idempotency_keys, order_status_history, order_items, orders RESTART IDENTITY CASCADE");
    }
}
