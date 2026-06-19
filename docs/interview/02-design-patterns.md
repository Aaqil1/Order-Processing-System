# 02 — Design patterns

This is the "what design patterns did you use?" answer, done properly. For each one: **what it is**, **why it's here**, and **the exact code** that demonstrates it. Don't recite GoF names you can't point at — point at these.

A useful framing for the interview: "I used *architectural* patterns (layering, repository, DTO) to structure the app, and a few *behavioural/concurrency* patterns (state machine, idempotency, optimistic locking) where the domain needed them. I deliberately avoided heavier patterns that don't earn their place at this scale."

---

## 1. Layered architecture

**What:** Horizontal layers (controller → service → repository), each depending only on the one below. Covered in depth in [01](./01-architecture-and-request-flow.md).

**Why:** Separation of concerns, testability, predictable structure.

**Proof:** The package layout (`controller`, `service`, `repository`, `domain`, `dto`, …).

---

## 2. Repository

**What:** An abstraction that hides *how* data is stored behind a collection-like interface. Callers say "save this order" / "find this order"; they never see SQL or JDBC.

**Why:** The service shouldn't care whether persistence is Postgres, an in-memory map, or a REST call. It also kills SQL injection for free (parameterized queries) and centralizes query logic.

**Proof:** Spring Data generates the implementation from an interface:

```java
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);   // derived query

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") UUID id);           // custom JPQL
}
```

`findByStatus` is a **derived query** — Spring parses the method *name* and writes the SQL. `findByIdWithItems` is a custom query when I need control (a fetch join). I never wrote a `OrderRepositoryImpl`.

---

## 3. DTO (Data Transfer Object)

**What:** A dedicated object for moving data across a boundary (here, the HTTP boundary), separate from the persistence entity.

**Why:** Decouples the API from the database schema, prevents lazy-loading serialization bugs, and hides internal fields (like the `version` lock column). The API can stay stable while the entity evolves.

**Proof:** Requests and responses are Java `record`s, and the entity is mapped to them explicitly:

```java
public record OrderResponse(
        UUID id, UUID customerId, String status, BigDecimal totalAmount,
        OffsetDateTime createdAt, OffsetDateTime updatedAt,
        List<OrderItemResponse> items, List<OrderStatusHistoryResponse> history) {

    // "summary" projection for the list endpoint — no items/history (lean payload)
    public static OrderResponse summary(Order order) { ... }

    // "detail" projection for get-by-id — full graph
    public static OrderResponse detail(Order order, List<OrderStatusHistory> history) { ... }
}
```

Note the two **projections**: list returns `summary` (small), get-by-id returns `detail` (full). Same DTO type, different amount of data — a small but real design decision.

`record`s are perfect for DTOs: immutable, no boilerplate, value-based `equals`/`hashCode`, and Jackson constructs them via the canonical constructor.

---

## 4. State machine (a behavioural pattern)

**What:** A finite set of states with explicitly allowed transitions; every other transition is illegal.

**Why:** Order status is not a free-form string — `PENDING → SHIPPED` should be impossible. Centralizing the rule avoids scattered `if` checks.

**Proof:**

```java
private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        PENDING,    Set.of(PROCESSING, CANCELLED),
        PROCESSING, Set.of(SHIPPED),
        SHIPPED,    Set.of(DELIVERED),
        DELIVERED,  Set.of(),
        CANCELLED,  Set.of());
```

Full treatment in [03 — state machine](./03-state-machine.md).

---

## 5. Dependency Injection / Inversion of Control

**What:** Objects don't create their own dependencies; they're handed them from outside (by the Spring container).

**Why:** Loose coupling and testability. In a test I can pass a mock or a real instance without touching the class. The class declares *what* it needs, not *how* to build it.

**Proof:** **Constructor injection** everywhere — no `@Autowired` on fields:

```java
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final OrderStateMachine stateMachine;
    // ...
    public OrderService(OrderRepository orderRepository, OrderStateMachine stateMachine, ...) {
        this.orderRepository = orderRepository;
        this.stateMachine = stateMachine;
    }
}
```

**Why constructor injection over field injection (a classic question):**
- Dependencies can be `final` → immutable, guaranteed set.
- The object is impossible to construct in an invalid state.
- Tests can `new OrderService(mockRepo, ...)` without Spring or reflection.
- Field injection (`@Autowired` on a private field) hides dependencies and needs reflection to test.

---

## 6. Transaction Script / Unit of Work

**What:** A business operation is wrapped so all its database work commits or rolls back together.

**Why:** Atomicity — an order without its items, or an order without its creation-history row, is corrupt data.

**Proof:** `@Transactional` on service methods; read methods use `readOnly = true`. See [05](./05-transactions-and-jpa.md).

---

## 7. Optimistic locking (concurrency pattern)

**What:** Instead of locking a row while you edit it, you let everyone read freely, but detect at write-time whether someone else changed it (via a `version` column).

**Why:** High concurrency without the contention and deadlock risk of pessimistic locks. Conflicts are rare, so "assume success, verify at commit" is cheaper.

**Proof:**

```java
@Version
@Column(nullable = false)
private long version;
```

Each update does `... SET version = version + 1 WHERE id = ? AND version = ?`. If the row was changed underneath you, 0 rows match → Hibernate throws `OptimisticLockingFailureException` → mapped to `409`. Full story in [04 — concurrency](./04-concurrency-and-cancel-race.md).

---

## 8. Conditional update / compare-and-set

**What:** Push the "check then act" into a single atomic SQL statement so the database — not application code — decides the winner.

**Why:** Eliminates the read-then-write race between cancel and the scheduler.

**Proof:**

```java
@Modifying
@Query("UPDATE Order o SET o.status = CANCELLED, o.version = o.version + 1 " +
       "WHERE o.id = :id AND o.status = PENDING")
int cancelIfPending(@Param("id") UUID id);
```

This is the highlight of the project. Deep dive in [04](./04-concurrency-and-cancel-race.md).

---

## 9. Idempotency key

**What:** A client-supplied token that lets the server recognize a retried request and return the original result instead of repeating the side effect.

**Why:** `POST` isn't naturally safe to retry; without this, a double-click creates two orders.

**Proof:** `IdempotencyKey` entity + UNIQUE constraint + the create-order logic. See [07](./07-idempotency.md).

---

## 10. Centralized exception handling (a cross-cutting pattern)

**What:** One place translates exceptions into HTTP responses, so controllers stay clean and every error looks the same.

**Why:** Without it, every controller would need try/catch and you'd get inconsistent error shapes.

**Proof:**

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(OrderNotFoundException.class)        // → 404
    @ExceptionHandler(InvalidStatusTransitionException.class)  // → 409
    @ExceptionHandler(OptimisticLockingFailureException.class) // → 409
    @ExceptionHandler(MethodArgumentNotValidException.class)   // → 400
}
```

Full treatment in [08](./08-validation-and-error-handling.md).

---

## 11. Audit log / append-only history

**What:** Every status change is recorded as a new immutable row rather than only mutating a single column.

**Why:** The requirement says "track their status." A history table answers "how did this order get here, and when?" — not just "where is it now?"

**Proof:** `order_status_history` table + `recordHistory(...)` called on create, status update, and cancel.

---

## What I deliberately did NOT use (say this — it shows judgment)

| Pattern | Why not here |
|---|---|
| **Microservices** | One bounded context; one DB. Splitting adds network + distributed-transaction cost for no benefit. |
| **CQRS / Event sourcing** | Reads and writes are simple and symmetric; no need to separate them or rebuild state from events. |
| **Saga** | No cross-service workflow — a single local transaction covers each operation. |
| **GoF Strategy / Factory for transitions** | The `ALLOWED` map *is* the strategy; a class hierarchy would be heavier for no gain. |
| **Generic Mapper framework (MapStruct/ModelMapper)** | A handful of explicit `from()` factory methods are clearer and dependency-free at this size. |
| **Hexagonal / ports & adapters** | Valuable at larger scale; here it would add indirection (interfaces for everything) without payoff. |

> The senior signal isn't knowing many patterns — it's knowing which ones the problem actually needs, and being able to point at the line of code for each.

---

## Likely interview questions

**Q: Which patterns did you use and why?**
Walk the list above, but lead with the three that carry the design: layered architecture (structure), state machine (correctness of status), and the conditional update + optimistic locking (concurrency). Name the rest briefly.

**Q: Field vs constructor injection?**
Constructor — `final` fields, no reflection needed to test, impossible to instantiate half-built. See pattern 5.

**Q: Is a state machine really a "design pattern"?**
It's the State pattern's intent (behaviour depends on internal state, transitions are explicit), implemented data-first as a transition map instead of a class-per-state. For five states a map is simpler and just as correct.

**Q: Why records for DTOs?**
Immutable, concise, value semantics, and Jackson supports them. They signal "data, not behaviour," which is exactly what a DTO is.
