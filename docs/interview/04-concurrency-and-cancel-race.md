# 04 — Concurrency & the cancel race

This is the centerpiece of the whole project. If you nail one topic, make it this one. It ties together race conditions, transactions, isolation, optimistic locking, and atomic SQL.

---

## The concept: a race condition

A **race condition** is when the correctness of a program depends on the *timing* of two or more operations that run concurrently. The "happy path" works when you test it alone; it breaks only when two things happen at nearly the same instant — which is exactly what production does and your laptop usually doesn't.

The classic shape is **read-then-write** (also called "check-then-act"):

```
1. read a value
2. make a decision based on what you read
3. write a new value
```

If another actor changes the value between steps 1 and 3, your decision in step 2 is now based on stale data, and your write in step 3 silently overwrites their change. This is called a **lost update**.

---

## Our specific race: cancel vs. the scheduler

Two things can change an order's status at the same time:

1. A **customer** calls `POST /orders/{id}/cancel` — wants `PENDING → CANCELLED`.
2. The **background job** (every 5 min) promotes pending orders — `PENDING → PROCESSING`.

Imagine an order that is `PENDING`, and both fire at the same millisecond.

### The WRONG implementation (what an AI assistant hands you first)

```java
// BUG: read-then-write
Order o = repo.findById(id).orElseThrow();   // 1. read  → status = PENDING
if (o.getStatus() == PENDING) {              // 2. decide → yes, it's pending
    o.setStatus(CANCELLED);
    repo.save(o);                            // 3. write → CANCELLED
}
```

Interleave it with the scheduler:

```
Time  Cancel thread                         Scheduler thread
----  -----------------------------------   --------------------------------
 t0   findById → status = PENDING
 t1                                          UPDATE ... SET PROCESSING WHERE PENDING  (commits)
 t2   status == PENDING? yes (STALE!)
 t3   save(CANCELLED)  ← overwrites PROCESSING
```

Result: an order the warehouse is already **processing** silently becomes **cancelled**. Two writers, last-write-wins, data corruption. The `if` check did nothing because the value it checked was already stale by the time the write happened.

---

## Fix #1 (the main one): atomic conditional update

Move the *check* and the *write* into a **single SQL statement** so they happen as one indivisible operation. The database evaluates the `WHERE` and applies the `SET` atomically, holding a row lock for the instant it takes.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Order o SET o.status = com.peerislands.orders.domain.OrderStatus.CANCELLED, " +
       "o.version = o.version + 1 " +
       "WHERE o.id = :id AND o.status = com.peerislands.orders.domain.OrderStatus.PENDING")
int cancelIfPending(@Param("id") UUID id);
```

The crucial part is `WHERE ... AND o.status = PENDING`. The statement only changes the row **if it is still PENDING at the moment the database executes it**. There's no window between check and write — they're the same operation.

The method returns the **number of rows updated**:
- returns `1` → the row was still PENDING, we cancelled it. ✅
- returns `0` → the row was no longer PENDING (the scheduler already promoted it, or it was already cancelled), so nothing was changed.

The service uses that count to decide the HTTP response:

```java
@Transactional
public OrderResponse cancel(UUID id) {
    int updated = orderRepository.cancelIfPending(id);
    if (updated == 0) {
        // Distinguish "doesn't exist" (404) from "exists but not cancellable" (409).
        Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        throw new InvalidStatusTransitionException(
                "Order " + id + " cannot be cancelled from status " + order.getStatus());
    }
    recordHistory(id, OrderStatus.PENDING, OrderStatus.CANCELLED);
    return detailById(id);
}
```

Now replay the race:

```
Time  Cancel thread                          Scheduler thread
----  ------------------------------------   -----------------------------------
 t0                                           UPDATE ... SET PROCESSING WHERE PENDING (1 row, commits)
 t1   UPDATE ... SET CANCELLED WHERE PENDING
      → 0 rows (it's PROCESSING now)
 t2   updated == 0 → 409 Conflict
```

Exactly one side wins, the row is never corrupted, and the loser gets a clean `409`. If cancel had gone first, the scheduler's `WHERE status = PENDING` simply wouldn't match that row, and it'd stay `CANCELLED`. The database is the single arbiter.

> One-liner for the interview: **"I turned a read-then-write into a compare-and-set by putting the condition in the `WHERE` clause, so the database resolves the race instead of my application code."**

### What is `@Modifying` doing?
By default `@Query` is a `SELECT`. `@Modifying` tells Spring Data this is an `UPDATE`/`DELETE`. The flags:
- `flushAutomatically = true` — flush pending entity changes *before* running this update, so it sees consistent data.
- `clearAutomatically = true` — clear the persistence context *after*, so stale cached entities don't mask the change you just made at the SQL level. (Important: the update bypasses the entity cache, so we evict it to avoid reading a stale `Order` afterwards.)

---

## Fix #2 (the safety net): optimistic locking with `@Version`

```java
@Version
@Column(nullable = false)
private long version;
```

**How it works:** Hibernate includes the version in every update's `WHERE` and increments it:

```sql
UPDATE orders SET status = ?, version = version + 1
WHERE id = ? AND version = ?      -- the version you originally read
```

If another transaction already bumped the version, your `WHERE` matches 0 rows. Hibernate notices "I expected to update 1 row but updated 0" and throws `OptimisticLockingFailureException`, which the global handler maps to `409`:

```java
@ExceptionHandler(OptimisticLockingFailureException.class)
public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
    return build(HttpStatus.CONFLICT, "The order was modified concurrently; please retry.", req);
}
```

This protects the **entity-based** path (`updateStatus`, which does `findById` then `setStatus`). Both the conditional update and the version bump increment `version`, so the two mechanisms reinforce each other.

### Optimistic vs. pessimistic locking (guaranteed question)

| | Optimistic (`@Version`) | Pessimistic (`SELECT ... FOR UPDATE`) |
|---|---|---|
| Assumption | conflicts are **rare** | conflicts are **frequent** |
| Mechanism | detect conflict at commit via version | lock the row up front, others wait |
| Cost | no DB locks held; cheap on the happy path | holds a lock; risk of contention/deadlock |
| On conflict | transaction fails → caller retries / gets 409 | the other transaction simply waited |
| Best for | web apps, low contention (this app) | hot rows, money transfers, queues |

**Why I chose optimistic here:** order rows are rarely updated by two actors at once, so paying for a lock on every update would be wasteful. The conditional `UPDATE` already makes cancel correct; `@Version` is the general guard for the read-modify-write path. If a specific row were extremely hot, I'd consider pessimistic locking for *that* operation only.

---

## How the test proves it

A unit test can't catch a race — you need real threads and a real database. `CancelVsPromoteConcurrencyTest` runs cancel and the scheduler **simultaneously** on the same order, 40 times:

```java
@Test
void cancelAndPromoteRaceLeavesExactlyOneWinner() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    for (int i = 0; i < ITERATIONS; i++) {
        UUID orderId = seedPendingOrder();
        CountDownLatch start = new CountDownLatch(1);   // a "starting gun"
        AtomicBoolean cancelWon = new AtomicBoolean(false);

        Future<?> cancelTask = pool.submit(() -> {
            awaitQuietly(start);                        // both threads wait here...
            try {
                orderService.cancel(orderId);
                cancelWon.set(true);
            } catch (InvalidStatusTransitionException expectedWhenLost) {
                cancelWon.set(false);
            }
        });
        Future<?> promoteTask = pool.submit(() -> {
            awaitQuietly(start);
            scheduler.promotePendingOrders();
        });

        start.countDown();                              // ...released together → max contention
        cancelTask.get(10, TimeUnit.SECONDS);
        promoteTask.get(10, TimeUnit.SECONDS);

        OrderStatus finalStatus = orderRepository.findById(orderId).orElseThrow().getStatus();

        // 1) the row is never corrupted / stuck
        assertThat(finalStatus).isIn(OrderStatus.CANCELLED, OrderStatus.PROCESSING);

        // 2) what the cancel call *reported* matches what's actually persisted
        if (cancelWon.get()) {
            assertThat(finalStatus).isEqualTo(OrderStatus.CANCELLED);
        } else {
            assertThat(finalStatus).isEqualTo(OrderStatus.PROCESSING);
        }
    }
}
```

Two techniques worth naming:
- **`CountDownLatch` as a starting gun:** both threads block on `start.await()`, and `start.countDown()` releases them at the same instant — maximizing the chance they actually collide. Without it, one thread would usually finish before the other started and the race would never trigger.
- **The invariant check:** the test doesn't assert *who* wins (that's non-deterministic) — it asserts the persisted state and the reported outcome **agree**, and the order is never left in a bad state. That's how you test non-deterministic code: assert the invariant, not the schedule.

This test **fails** if you paste the naive read-then-write cancel back in — both threads read PENDING, both "succeed," but the final state contradicts one of them. That failure is the proof the fix matters.

---

## Bonus concept: transaction isolation (if they push deeper)

Databases offer **isolation levels** that trade consistency for concurrency:

- **READ COMMITTED** (Postgres default): you only see committed data, but a row can change between two reads in the same transaction.
- **REPEATABLE READ / SERIALIZABLE**: stronger guarantees, more conflicts/aborts.

Our correctness does **not** rely on a stronger isolation level — the atomic `UPDATE ... WHERE status = PENDING` is correct even under READ COMMITTED, because a single UPDATE statement takes a row lock and re-evaluates the predicate against the latest committed row. That's a nice thing to say: *"I didn't need SERIALIZABLE; the conditional update is correct at the default isolation level."*

---

## Likely interview questions

**Q: Walk me through the cancel race.**
Use the two timelines above: read-then-write loses an update; the conditional `UPDATE ... WHERE status = PENDING` makes check-and-set atomic so the DB picks the winner; 0 rows → 409.

**Q: Why not just synchronize the Java method / use a lock?**
A `synchronized` block or a JVM lock only works within **one** instance. The scheduler and the web request could be on different threads now, but on different *instances* in production — a JVM lock wouldn't see across them. The database row is the one place both actors share, so the guarantee has to live there.

**Q: Why not pessimistic locking?**
Conflicts are rare, so optimistic + conditional update is cheaper and avoids holding locks/deadlock risk. I'd reserve `SELECT FOR UPDATE` for genuinely hot rows.

**Q: What does the `version` column actually do?**
It's the optimistic-lock token. Every update sets `version = version + 1 WHERE version = <what I read>`. A concurrent change makes that `WHERE` miss, Hibernate sees 0 rows updated and throws — turning a silent lost update into an explicit `409`.

**Q: How can you test a race deterministically?**
You can't make the *schedule* deterministic, so you (a) force contention with a `CountDownLatch` starting gun and many iterations, and (b) assert an **invariant** that must hold for every interleaving (exactly one winner, state never corrupted), not a specific outcome.

**Q: Does this need SERIALIZABLE isolation?**
No. A single conditional `UPDATE` is atomic at READ COMMITTED — it locks and re-checks the row. Stronger isolation would add aborts for no benefit here.

**Q: The scheduler runs on two instances — does your fix still hold?**
The cancel correctness holds (it's enforced in the DB). But the *scheduler firing twice* is a separate concern — see [06 — scheduling](./06-scheduling-background-job.md) and ShedLock.
