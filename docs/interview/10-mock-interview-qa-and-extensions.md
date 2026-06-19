# 10 — Mock interview Q&A + live extension drills

Two parts:
- **Part A:** 25 likely questions with model answers (2–3 sentences each — say them out loud).
- **Part B:** 6 live-coding extension drills with the exact diffs, so a "now add X" request is a 5-minute change you've already rehearsed.

---

## Part A — Mock Q&A

### Architecture & design

**1. Walk me through your project.**
A layered Spring Boot monolith over PostgreSQL: controller (HTTP), service (business logic + transactions + state machine), repository (Spring Data JPA). Five REST operations, a scheduled job promoting PENDING→PROCESSING, Flyway-owned schema, and tests at three levels including a concurrency test for the cancel race.

**2. Why a monolith, not microservices?**
One bounded context, one database, five operations. A monolith uses local transactions and is far simpler to deploy and operate. I'd only split at a real boundary (e.g. publish `OrderPlaced` to decouple inventory/fulfillment) if the domain demanded it.

**3. Why layered? Isn't it overkill?**
The layers are nearly free and each has a real job — HTTP, rules, persistence. They make logic testable and the code predictable. Overkill would be extra layers (CQRS, hexagonal) that don't earn their place at this size.

**4. Why thin controllers?**
HTTP is just a delivery mechanism. Keeping rules in the service means a second entry point (gRPC, messaging) wouldn't duplicate logic, and the controller stays trivial to read.

**5. Why DTO records instead of returning entities?**
Decouples API from schema, prevents lazy-loading serialization errors, and hides internals like the `version` column. Records are immutable, concise, and Jackson-friendly.

### State machine

**6. How do status transitions work?**
A single `ALLOWED` map in `OrderStateMachine` defines legal transitions; everything else throws `InvalidStatusTransitionException` → `409`. Centralized so adding a transition is a one-line change.

**7. Why 409 for an illegal transition, not 400?**
`400` is for malformed requests; `409` is for a well-formed request that conflicts with the resource's current state. `PROCESSING→DELIVERED` is valid JSON, just not allowed now.

**8. How would you add a RETURNED status live?**
Add the enum value and one entry to the `ALLOWED` map (e.g. `DELIVERED → RETURNED`). No controller/service change. (Drill 1 below.)

### Concurrency (expect the most depth here)

**9. Explain the cancel race.**
Cancel and the scheduler can both touch a PENDING order at once. Read-then-write loses an update: cancel reads PENDING, the job flips it to PROCESSING, cancel overwrites it to CANCELLED. I fixed it with an atomic `UPDATE ... WHERE status = PENDING`, so the DB decides the winner.

**10. How does `cancelIfPending` work?**
It's one SQL statement that only updates if the row is still PENDING. It returns the row count: 1 = cancelled, 0 = no longer cancellable → the service returns 409. Check and write are atomic, so there's no race window.

**11. What's the `@Version` column for?**
Optimistic locking. Each update does `... WHERE id=? AND version=?` and increments version; a concurrent change makes that match 0 rows, Hibernate throws `OptimisticLockingFailureException` → 409. It guards the read-modify-write path (`updateStatus`).

**12. Optimistic vs pessimistic locking — why optimistic?**
Conflicts are rare here, so detecting them at commit is cheaper than holding row locks (which risk contention/deadlock). Pessimistic (`SELECT FOR UPDATE`) is for hot rows. The conditional update already makes cancel correct.

**13. Why not a `synchronized` block or a JVM lock?**
That only works within one JVM. In production there can be multiple instances; the only thing they share is the database row, so the guarantee must live in the DB, not in Java memory.

**14. Do you need SERIALIZABLE isolation?**
No. A single conditional `UPDATE` is atomic at the default READ COMMITTED — it locks and re-checks the row. Stronger isolation would just add aborts.

**15. How did you test the race?**
Two threads, a `CountDownLatch` starting gun, 40 iterations, asserting the invariant (exactly one winner, never corrupted) rather than a fixed outcome. It fails against the naive read-then-write version — which is the proof.

### Transactions & JPA

**16. What does `@Transactional` guarantee on create?**
Atomicity: the order, its items, and the first history row all commit together or roll back together. You can't get an order with no items.

**17. In `updateStatus` you never call `save()` — why does the change persist?**
Dirty checking. The entity is loaded inside the transaction (managed), so Hibernate compares it to its load-time snapshot at commit and emits the UPDATE automatically.

**18. What's the N+1 problem and how do you avoid it?**
Loading N parents then lazily loading each one's children = 1+N queries. I use `LEFT JOIN FETCH` in `findByIdWithItems` to load order+items in one query, and the list endpoint doesn't load items at all.

**19. Why Flyway over `ddl-auto`?**
Versioned, reviewable, repeatable migrations. `ddl-auto: validate` only checks entity/schema agreement and fails fast on drift; auto-DDL is unsafe and can't do data migrations or rollbacks.

**20. Why UUID and BigDecimal?**
UUID ids don't leak volume via sequential numbers and let me know the id before insert. `BigDecimal` represents money exactly; `double` can't (`0.1+0.2 != 0.3`).

### Scheduling & idempotency

**21. Why `@Scheduled` and not Kafka?**
The requirement is time-based ("every 5 minutes"); a timer is the minimal correct tool. A queue is for event-driven/high-throughput work — over-engineering here.

**22. Two instances run the scheduler — problem?**
It fires on both → runs twice per tick. Fix with ShedLock (a distributed lock in Postgres/Redis) so exactly one runs. Not wired because this is single-instance.

**23. Why is the promotion one UPDATE, not a loop?**
A set-based `UPDATE ... WHERE status=PENDING` is one indexed statement; a loop is N selects + N updates and loads every row into memory. The set-based version scales.

**24. What's the idempotency key for?**
To make `POST /orders` safe to retry. Same key → return the original order instead of creating a duplicate. The `UNIQUE(idem_key)` constraint guarantees correctness even when two identical requests arrive simultaneously.

**25. The AI-usage question: where did AI help and where did you correct it?**
AI sped up CRUD scaffolding. The two new-to-me concepts were the scheduled job and the idempotency key, and AI's first attempts were happy-path: a per-row loop instead of a set-based update, a read-then-write cancel with a race, and a check-then-insert idempotency that still duplicated under concurrency. I fixed each by pushing correctness into the database and proving it with tests.

---

## Part B — Live extension drills (rehearse by actually typing them)

For each: the likely prompt, where the change goes, and the diff. Saying *"this rule lives in X, so I'll change X"* before typing is half the score.

### Drill 1 — Add a `RETURNED` status (`DELIVERED → RETURNED`)

**Where:** enum + state machine map. Nothing else.

`OrderStatus.java`:
```java
public enum OrderStatus { PENDING, PROCESSING, SHIPPED, DELIVERED, CANCELLED, RETURNED }
```

`OrderStateMachine.java`:
```java
private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        PENDING,    Set.of(PROCESSING, CANCELLED),
        PROCESSING, Set.of(SHIPPED),
        SHIPPED,    Set.of(DELIVERED),
        DELIVERED,  Set.of(RETURNED),   // was Set.of()
        CANCELLED,  Set.of(),
        RETURNED,   Set.of());          // new terminal state
```
Say: *"Because transitions are centralized, this is the whole change. The PATCH endpoint already enforces it."*

### Drill 2 — Filter the list by `customerId`

**Where:** repository method + service + controller param.

`OrderRepository.java`:
```java
Page<Order> findByCustomerId(UUID customerId, Pageable pageable);
Page<Order> findByCustomerIdAndStatus(UUID customerId, OrderStatus status, Pageable pageable);
```

`OrderService.java` (extend `listOrders` signature):
```java
public PageResponse<OrderResponse> listOrders(OrderStatus status, UUID customerId, Pageable pageable) {
    Page<Order> page;
    if (customerId != null && status != null)      page = orderRepository.findByCustomerIdAndStatus(customerId, status, pageable);
    else if (customerId != null)                   page = orderRepository.findByCustomerId(customerId, pageable);
    else if (status != null)                       page = orderRepository.findByStatus(status, pageable);
    else                                           page = orderRepository.findAll(pageable);
    return PageResponse.from(page, OrderResponse::summary);
}
```

`OrderController.java`:
```java
@GetMapping
public PageResponse<OrderResponse> list(
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(required = false) UUID customerId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    // ...build pageable...
    return orderService.listOrders(status, customerId, pageable);
}
```
Mention: there's already an `idx_orders_customer` index, so this stays fast. If the filter combinations grow, switch to a JPA `Specification` instead of method explosion.

### Drill 3 — Ownership check (customer can only cancel their own order)

**Where:** service (a guard), even without real auth.

```java
@Transactional
public OrderResponse cancel(UUID id, UUID requestingCustomerId) {
    Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    if (!order.getCustomerId().equals(requestingCustomerId)) {
        throw new OrderNotFoundException(id);   // 404, not 403 — don't reveal it exists (anti-IDOR)
    }
    int updated = orderRepository.cancelIfPending(id);
    // ...rest unchanged...
}
```
Say: *"In production `requestingCustomerId` comes from the JWT, not a param. Returning 404 instead of 403 avoids leaking that someone else's order exists — an IDOR-hardening choice."*

### Drill 4 — Promote only orders older than N minutes

**Where:** the repository query.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Order o SET o.status = PROCESSING, o.version = o.version + 1 " +
       "WHERE o.status = PENDING AND o.createdAt < :cutoff")
int promotePendingOlderThan(@Param("cutoff") OffsetDateTime cutoff);
```
Scheduler:
```java
int promoted = orderRepository.promotePendingOlderThan(OffsetDateTime.now().minusMinutes(10));
```
Still one set-based statement; just a tighter predicate.

### Drill 5 — Update an order's items while PENDING

**Where:** new service method (guarded by status) + controller endpoint.

```java
@Transactional
public OrderResponse updateItems(UUID id, List<CreateOrderItemRequest> newItems) {
    Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    if (order.getStatus() != OrderStatus.PENDING) {
        throw new InvalidStatusTransitionException("Items can only be changed while PENDING");  // 409
    }
    order.getItems().clear();                       // orphanRemoval=true deletes old rows
    newItems.forEach(i -> order.addItem(new OrderItem(UUID.randomUUID(), i.productId(), i.quantity(), i.unitPrice())));
    // recompute total, save (or rely on dirty checking)
    return detailById(id);
}
```
Mention: `orphanRemoval = true` is what makes `clear()` delete the old item rows — ties back to [05](./05-transactions-and-jpa.md).

### Drill 6 — Run/break the concurrency test (show, don't tell)

If they ask you to *demonstrate* the race:
1. Run `CancelVsPromoteConcurrencyTest` → green.
2. Temporarily replace `cancel()` with the naive read-then-write version.
3. Re-run → it fails (final status contradicts the reported winner).
4. Revert. Say: *"The test encodes the invariant, so it catches the regression the moment the safe update is removed."*

---

## Final-hour checklist

- [ ] Re-read files 01–09 with the repo open.
- [ ] Type out Drills 1–3 once on your machine (the most likely asks).
- [ ] Say the 60-second summary (in the index) from memory.
- [ ] Have these files open in tabs: `OrderController`, `OrderService`, `OrderRepository`, `OrderStateMachine`, `OrderPromotionScheduler`, `CancelVsPromoteConcurrencyTest`, `AI_USAGE.md`.
- [ ] Run `./mvnw test` once so you've seen it green.
- [ ] Prepare 2 questions for them (their stack, how they deploy, on-call).

## Behaviour in the room

- Narrate intent before code: *"This belongs in the service because…"*
- If unsure, think out loud and state assumptions — they're evaluating reasoning, not perfection.
- When you change code, mention what test would cover it.
- It's fine to say *"I'd reach for X but it's out of scope here because Y"* — that's the senior signal throughout this project.
