# 05 — Transactions & JPA

This file covers the persistence concepts: `@Transactional`, the persistence context and dirty checking, cascading, lazy loading, the N+1 problem, and `JOIN FETCH`. These are the "explain how JPA actually works" questions.

---

## Part A — Transactions

### The concept: ACID and the transaction boundary

A **transaction** is a unit of work that is **all-or-nothing**. ACID:
- **Atomicity** — all statements commit, or none do.
- **Consistency** — the DB moves from one valid state to another (constraints hold).
- **Isolation** — concurrent transactions don't see each other's half-done work.
- **Durability** — once committed, it survives a crash.

The interview-relevant one is **atomicity**: when we create an order we write to *three* tables (`orders`, `order_items`, `order_status_history`). If the second insert failed and the first had already committed, we'd have an order with no items — corrupt. A transaction guarantees they all land together or not at all.

### How `@Transactional` works in Spring

```java
@Transactional
public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
    // ... save(order), recordHistory(), save(idempotencyKey) ...
}
```

- Spring wraps the method in a **proxy**. On entry it opens a transaction (gets a DB connection, `BEGIN`). On normal return it **commits**. If a **runtime exception** propagates out, it **rolls back**.
- Default rollback rule: rolls back on `RuntimeException` and `Error`, **not** on checked exceptions (you can change this with `rollbackFor`). All our exceptions (`OrderNotFoundException`, `InvalidStatusTransitionException`, `DataIntegrityViolationException`) are unchecked, so they roll back correctly.

### Read-only transactions

```java
@Transactional(readOnly = true)
public OrderResponse getOrder(UUID id) { return detailById(id); }
```

`readOnly = true` is an optimization hint: Hibernate skips dirty-checking (it won't try to detect/flush changes), and the driver/DB can optimize. Use it on pure reads.

### The proxy gotcha (a senior trap question)

Because `@Transactional` works via a proxy, **calling a `@Transactional` method from another method in the same class does NOT start a transaction** — the call doesn't go through the proxy. Example:

```java
public void a() { b(); }            // internal call — proxy is bypassed
@Transactional public void b() {}   // NOT transactional when called from a()
```

To make `b()` transactional, call it from *another bean*, or self-inject the proxy. In this codebase every `@Transactional` method is a public entry point called from the controller (through the proxy), so we're fine — but knowing this trap is a strong signal.

### Propagation (one-line awareness)
Default propagation is `REQUIRED`: join an existing transaction if one is active, else start a new one. So when `createOrder` calls `recordHistory` and `save`, they all run in the *same* transaction. Other modes (`REQUIRES_NEW`, `NESTED`) exist for special cases; we don't need them here.

---

## Part B — The persistence context & dirty checking

### The concept

Within a transaction, Hibernate keeps a **persistence context** (a.k.a. first-level cache / unit of work) — a map of all *managed* entities it has loaded. A managed entity is one Hibernate is tracking.

**Dirty checking:** at flush/commit time, Hibernate compares each managed entity to a snapshot it took when the entity was loaded. If a field changed, it auto-generates the `UPDATE`. **You don't call `save()` for updates.**

### Where we rely on it

```java
@Transactional
public OrderResponse updateStatus(UUID id, OrderStatus target) {
    Order order = orderRepository.findById(id).orElseThrow(...);  // now "managed"
    stateMachine.assertCanTransition(order.getStatus(), target);
    order.setStatus(target);    // just a setter — no save() call
    recordHistory(id, current, target);
    return detailById(id);
}
// On commit, Hibernate sees status changed and emits UPDATE orders SET status=?, version=?...
```

Notice there is **no `orderRepository.save(order)`** after `setStatus`. The entity is managed (we loaded it in this transaction), so dirty checking flushes the change automatically at commit. This surprises people — it's worth explaining clearly.

---

## Part C — Relationships, cascade, and orphan removal

### The mapping

```java
// Order.java — the "one" side, owns the cascade
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
private List<OrderItem> items = new ArrayList<>();

// OrderItem.java — the "many" side, owns the FK column
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "order_id", nullable = false)
private Order order;
```

- **`mappedBy = "order"`** means `OrderItem.order` owns the foreign key; `Order.items` is the inverse side. (The owning side is the one with `@JoinColumn` / the FK.)
- **`cascade = CascadeType.ALL`** — operations on `Order` cascade to its items. So `save(order)` also inserts the items; deleting the order deletes them.
- **`orphanRemoval = true`** — if you remove an item from the `items` list, that row is deleted from the DB. (Difference from cascade: orphan removal fires when a child is *disassociated*, not just when the parent is deleted.)

### The helper that keeps both sides in sync

```java
public void addItem(OrderItem item) {
    item.setOrder(this);     // set the owning side (the FK)
    this.items.add(item);    // and the inverse collection
}
```

You must set *both* sides of a bidirectional relationship in memory, or the in-memory object graph disagrees with what gets persisted. This helper centralizes that.

---

## Part D — Lazy loading and the N+1 problem

### Lazy vs eager

`fetch = FetchType.LAZY` means the items are **not** loaded when you load the order — Hibernate loads them only when you first touch `order.getItems()`. The opposite, `EAGER`, always loads them. Lazy is the sane default (don't pay for data you might not use), but it has two consequences:

1. **`LazyInitializationException`** if you access the collection *after* the transaction/session closed. This is another reason we map to DTOs *inside* the transactional service method — by the time the controller serializes, the data is already materialized.
2. **The N+1 problem** (below).

### The N+1 problem

If you load 20 orders and then loop and touch each order's items, lazy loading fires **1** query for the orders + **N=20** queries for the items = 21 queries. That's the N+1 problem: one query becomes N+1, and it silently destroys performance as data grows.

### Our fix: `JOIN FETCH`

For get-by-id we load the order and its items in **one** query:

```java
@Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
Optional<Order> findByIdWithItems(@Param("id") UUID id);
```

- `JOIN FETCH` tells Hibernate to grab the association in the *same* SQL `SELECT` (a SQL join), not in a separate lazy query.
- `LEFT` (outer) join so an order with **zero** items is still returned (an inner join would drop it).

### Why the list endpoint does NOT fetch items

```java
public static OrderResponse summary(Order order) {
    // items = null, history = null  — list view is intentionally lightweight
}
```

The list endpoint returns a **summary** without items/history, so there's no N+1 there at all — and the payload stays small. Get-by-id returns the **detail** with a fetch join. Choosing *per endpoint* how much of the graph to load is the real lesson.

---

## Part E — A few entity details worth defending

```java
@Id
private UUID id;                                  // app-assigned UUID, not @GeneratedValue

@Column(name = "total_amount", precision = 12, scale = 2)
private BigDecimal totalAmount;                   // money = BigDecimal, never double

@Version
private long version;                             // optimistic lock

@CreationTimestamp  private OffsetDateTime createdAt;
@UpdateTimestamp    private OffsetDateTime updatedAt;

protected Order() { }                             // no-arg ctor required by JPA
```

- **UUID, app-assigned:** we set the id in code (`UUID.randomUUID()`), so we know the id before insert (handy for the `Location` header and history rows) and avoid leaking volume via sequential ids.
- **`BigDecimal` for money:** `double`/`float` can't represent decimal currency exactly (`0.1 + 0.2 != 0.3`), which corrupts totals. `BigDecimal` with fixed scale is correct.
- **`protected` no-arg constructor:** JPA/Hibernate instantiates entities via reflection using a no-arg constructor, then sets fields. It's `protected` (not `public`) to signal "framework only — application code should use the real constructor." Required; without it Hibernate throws `InstantiationException`. (DTits `record`s don't need this — Jackson uses their canonical constructor.)
- **`@CreationTimestamp` / `@UpdateTimestamp`:** Hibernate fills these automatically on insert/update.

---

## Part F — Flyway vs `ddl-auto` (schema ownership)

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # Hibernate does NOT create/alter tables
  flyway:
    enabled: true
    locations: classpath:db/migration
```

- The schema is defined by versioned **Flyway** migration SQL (`V1__init.sql`), which runs on startup.
- `ddl-auto: validate` means Hibernate only **checks** that the entities match the existing schema and **fails fast** at startup if they've drifted apart — it never silently changes the database.
- **Why not `ddl-auto: update`?** Auto-DDL is unpredictable, unreviewable, and dangerous in production (it can't do data migrations, can't be rolled back, and may make destructive guesses). Treating the DB as a managed, versioned asset is the professional choice.

---

## Likely interview questions

**Q: When does an update get written if you never call save()?**
On transaction commit (or an explicit flush). Hibernate dirty-checks managed entities against their load-time snapshot and emits `UPDATE`s for changed fields. `updateStatus` relies on exactly this.

**Q: What's the N+1 problem and how do you avoid it?**
One query for parents triggers one lazy query per child = N+1. Avoid it with a fetch join (`JOIN FETCH`), an entity graph, or batch fetching. We use `LEFT JOIN FETCH` in `findByIdWithItems`, and the list endpoint avoids it entirely by not loading items.

**Q: LAZY vs EAGER?**
LAZY defers loading until accessed (default for collections; avoids over-fetching) but can throw `LazyInitializationException` outside a session and cause N+1. EAGER always loads. I keep associations LAZY and fetch explicitly where needed.

**Q: Why DTOs instead of returning entities? (persistence angle)**
Mapping happens inside the transaction, so lazy associations are materialized safely; the client never triggers lazy loads or sees internal fields like `version`.

**Q: `cascade` vs `orphanRemoval`?**
Cascade propagates an operation (persist/remove) from parent to child. Orphan removal deletes a child when it's removed from the parent's collection (disassociated), even without deleting the parent.

**Q: Why Flyway over `ddl-auto`?**
Versioned, reviewable, repeatable migrations; `validate` catches entity/schema drift at startup; auto-DDL is unsafe and can't express data migrations or rollbacks.

**Q: The self-invocation `@Transactional` trap?**
Calling a `@Transactional` method from within the same bean bypasses the proxy, so no transaction starts. Call it from another bean or via the injected proxy. (Not an issue in this code — all transactional methods are external entry points.)
