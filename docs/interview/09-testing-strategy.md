# 09 — Testing strategy

The assignment grades testing explicitly. The story here is: **three layers of tests, each proving something the others can't**, and **integration tests against a real database, not mocks**.

---

## The test pyramid (the concept)

```
        /\        few, slow, broad
       /  \       ── End-to-end / concurrency
      /----\      ── Integration (real DB)
     /------\     ── Unit (fast, isolated)
    /--------\    many, fast, narrow
```

- **Unit tests:** one class, no framework, microseconds. Many of them.
- **Integration tests:** several components wired together against real infrastructure. Fewer, slower.
- **End-to-end / concurrency:** the whole thing under realistic conditions. Fewest, slowest.

The skill is putting each test at the *right* level: pure logic as a unit test, SQL/transaction behaviour as integration, races as concurrency.

---

## Layer 1 — Unit test: the state machine

The state machine is pure logic (a map lookup), so it needs **no Spring, no database** — instant to run.

```java
class OrderStateMachineTest {
    private final OrderStateMachine sm = new OrderStateMachine();   // just `new` it

    @ParameterizedTest
    @CsvSource({ "PENDING,PROCESSING", "PENDING,CANCELLED", "PROCESSING,SHIPPED", "SHIPPED,DELIVERED" })
    void legalTransitionsAllowed(OrderStatus from, OrderStatus to) {
        assertThat(sm.canTransition(from, to)).isTrue();
    }

    @Test
    void illegalTransitionsRejected() {
        // every pair NOT in the legal set must be false
    }
}
```

**Why unit here:** there's no I/O — wiring up Spring or a DB would only slow it down and prove nothing extra. Pure logic → pure unit test.

---

## Layer 2 — Integration tests against a real PostgreSQL (Testcontainers)

### The concept: why not mock the repository?

A tempting "integration" test mocks the repository:

```java
when(orderRepository.cancelIfPending(id)).thenReturn(1);   // mock
```

This proves **nothing** about the real system. A mock can't:
- run real SQL (so `cancelIfPending`'s `WHERE status = PENDING` is never actually executed),
- enforce a real `UNIQUE` or `CHECK` constraint,
- exhibit real transaction/locking behaviour,
- catch a race condition.

You'd be testing your mock setup, not your code.

### The solution: Testcontainers

**Testcontainers** spins up a **real PostgreSQL 16 in a Docker container** for the tests, runs your real Flyway migrations against it, and tears it down afterward. The tests exercise real SQL, real constraints, real transactions.

```java
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static { POSTGRES.start(); }   // singleton pattern — see below

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE idempotency_keys, order_status_history, order_items, orders RESTART IDENTITY CASCADE");
    }
}
```

- **`@ServiceConnection`** (Spring Boot 3.1+) auto-wires the container's JDBC URL/credentials into Spring — no manual `@DynamicPropertySource` for the datasource. Spring points at the container automatically.
- **`@BeforeEach` TRUNCATE** gives each test a clean database, so tests don't leak state into each other (important because the container is shared — see next).

### The singleton container pattern (a subtle but real point)

The usual `@Testcontainers` + `@Container` annotations start and **stop** the container per test class. But Spring **caches and reuses** the application context across test classes for speed. If the container stops after class 1 but class 2 reuses the cached context (pointing at the now-dead container), you get `Connection refused`.

The fix: start the container **once** in a `static {}` block and never let JUnit stop it. Testcontainers' "Ryuk" reaper kills it when the JVM exits. So **one** container is shared by **all** integration test classes, matching the **one** cached Spring context.

> This is a genuine bug I hit and fixed — a great thing to mention as "something I debugged."

### What the integration tests cover

`OrderApiIntegrationTest` (via `MockMvc`, full HTTP→DB):
- create with multiple items → `201`, items persisted, total computed, one history row
- get by id → `200`; unknown id → `404`
- list with/without status filter; pagination (`size=1`, empty result)
- cancel a PENDING order → `200`/`CANCELLED`; cancel again or cancel SHIPPED → `409`
- update status legal path → `200`; illegal skip / terminal → `409`
- validation: empty items, quantity 0 → `400`
- idempotency: same key twice → same order id

`OrderPromotionSchedulerIntegrationTest`:
- seed PENDING, invoke the job, assert it becomes PROCESSING
- non-PENDING orders are left untouched

---

## Layer 3 — The concurrency test

Covered fully in [04 — concurrency](./04-concurrency-and-cancel-race.md). The essence:

- Two threads (`ExecutorService` of size 2) run **cancel** and **promote** on the same order.
- A `CountDownLatch` releases them simultaneously to force contention.
- Repeated 40 times.
- Asserts an **invariant** (exactly one winner; status never corrupted), not a fixed outcome — because the schedule is non-deterministic.
- **Fails against the naive read-then-write cancel** — that failure is the proof the conditional update matters.

**Why this can't be a unit test:** a race only exists with real threads hitting a real database with real row locks. Mocks and single-threaded tests can't reproduce it.

---

## Test tooling notes (you may be asked)

- **JUnit 5** (`@Test`, `@ParameterizedTest`, `@CsvSource`) — parameterized tests keep the state-machine cases concise.
- **AssertJ** (`assertThat(...).isEqualTo(...)`) — fluent, readable assertions.
- **MockMvc** — drives the controllers through the full Spring MVC stack (routing, validation, serialization, exception handling) without a real network port.
- **`@SpringBootTest`** — boots the full application context for integration tests.
- There's a real environment detail in `pom.xml`: the Docker Engine API version is pinned (`docker.api.version`) so Testcontainers works on newer Docker daemons. (See the README troubleshooting section — another "thing I debugged" story.)

---

## What I deliberately did NOT test

- Framework code (Spring, Hibernate) — not my job to test the library.
- Getters/setters and trivial DTO mapping — no logic, no value.
- Coverage for its own sake — I test behaviour and edges that matter (the state machine, the race, the SQL), not lines for a percentage.

Saying this shows you test with intent, not to chase a coverage number.

---

## Likely interview questions

**Q: Why Testcontainers instead of H2 or mocks?**
Mocks don't run SQL or enforce constraints — they'd "pass" while the real query is wrong. H2 is a *different* database (different SQL dialect, no real Postgres locking semantics), so it can hide Postgres-specific bugs. Testcontainers runs the *same* Postgres I deploy, so the tests exercise real SQL, constraints, and transaction behaviour.

**Q: How do you test a race condition?**
Real threads + real DB, a `CountDownLatch` to fire them simultaneously, many iterations, and assert an invariant that must hold for *every* interleaving (exactly one winner, never corrupted) rather than a specific result.

**Q: Unit vs integration — how do you decide?**
If the thing under test is pure logic (state machine), unit-test it — fast and isolated. If correctness depends on SQL, constraints, transactions, or concurrency, it must be an integration test against the real DB, or the test is meaningless.

**Q: What's the singleton container pattern and why?**
Start one Testcontainers Postgres in a static block and never stop it via JUnit's lifecycle, so it's shared across all test classes and matches Spring's cached context. Avoids "connection refused" when a later class reuses the context after the per-class container was torn down.

**Q: How do you isolate tests that share one database?**
`TRUNCATE ... RESTART IDENTITY CASCADE` in `@BeforeEach`, so every test starts from an empty, known state.

**Q: How do you test the scheduler without waiting 5 minutes?**
Push the interval/initial-delay far into the future (so it never auto-fires) and invoke `promotePendingOrders()` directly in the test.
