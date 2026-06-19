# 06 — Scheduling / the background job

## The requirement

> "A background job should automatically update PENDING orders to PROCESSING every 5 minutes."

So we need recurring work that runs on a timer, with no HTTP request triggering it.

---

## The concept: scheduled (cron-style) background processing

There are broadly two ways to do background work:

1. **Time-triggered (scheduling):** "run this every N minutes / at this cron time." Simple, no infrastructure. Spring's `@Scheduled` does this.
2. **Event-triggered (message queue):** "run this when an event arrives" (Kafka, RabbitMQ, SQS). Decoupled and scalable, but heavier.

The requirement is explicitly time-based ("every 5 minutes"), so `@Scheduled` is the right, minimal tool. Reaching for Kafka here would be over-engineering — a good thing to say.

---

## How it's enabled

Scheduling is off by default in Spring Boot; you turn it on once:

```java
@SpringBootApplication
@EnableScheduling
public class OrderProcessingApplication { ... }
```

`@EnableScheduling` tells Spring to scan for `@Scheduled` methods and run them on a managed thread pool.

---

## The job itself

```java
@Component
public class OrderPromotionScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderPromotionScheduler.class);
    private final OrderRepository orderRepository;

    public OrderPromotionScheduler(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Scheduled(
            fixedRateString = "${orders.scheduler.promote-pending.fixed-rate-ms:300000}",
            initialDelayString = "${orders.scheduler.promote-pending.initial-delay-ms:0}")
    @Transactional
    public void promotePendingOrders() {
        int promoted = orderRepository.promotePending();
        if (promoted > 0) {
            log.info("Promoted {} order(s) PENDING -> PROCESSING", promoted);
        }
    }
}
```

### Why `fixedRateString` from a property?

`@Scheduled` accepts either a literal (`fixedRate = 300000`) or a string that resolves a property (`fixedRateString = "${...:300000}"`). I used the property form so the interval is **configurable** — 5 minutes (300000 ms) in production, but a few seconds in local testing via an env var, without recompiling:

```bash
ORDERS_PROMOTE_FIXED_RATE_MS=20000 docker compose up   # promote every 20s for a demo
```

`:300000` is the default if the property isn't set.

### `fixedRate` vs `fixedDelay` vs `cron` (know the difference)

| Option | Meaning |
|---|---|
| `fixedRate` | start every N ms, measured from each run's **start** (runs can overlap if one is slow) |
| `fixedDelay` | wait N ms **after the previous run finishes** before starting the next |
| `cron` | calendar-based, e.g. `0 0 * * * *` (top of every hour) |

I used `fixedRate` because the requirement is "every 5 minutes" regardless of run duration. If the job were long and I wanted to avoid overlap, `fixedDelay` would be safer.

### `initialDelay`

`initialDelayString` delays the *first* run after startup. It defaults to 0 here, but the tests set it to a huge value so the job never auto-fires during a test and the test can invoke it explicitly (see below).

---

## The query: one set-based statement, not a loop

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Order o SET o.status = PROCESSING, o.version = o.version + 1 " +
       "WHERE o.status = PENDING")
int promotePending();
```

This is important. The naive approach is:

```java
// DON'T: load every pending order and update one by one
List<Order> pending = repo.findByStatus(PENDING);
for (Order o : pending) { o.setStatus(PROCESSING); repo.save(o); }   // N selects + N updates
```

For 10,000 pending orders that's ~20,000 SQL statements and a lot of memory. The set-based version is **one** `UPDATE` that the database executes efficiently against the `idx_orders_status` index. It returns the count of rows changed, which we log.

> One-liner: **"Let the database do set operations; don't pull rows into the app to loop over them."**

It also bumps `version` so it cooperates with optimistic locking on any row a concurrent transaction is holding.

---

## The production gotcha: multiple instances (the senior question)

`@Scheduled` fires **on every instance** of the app. If you run 2 instances for high availability, the job runs **twice** every tick — both try to promote, doing duplicate work (here it's idempotent-ish since the second finds nothing to promote, but in general double-firing is a real bug, e.g. sending an email twice).

### The fix: a distributed lock (ShedLock)

[ShedLock](https://github.com/lukas-krecan/ShedLock) lets exactly one instance run each scheduled tick by acquiring a lock in a shared store (Postgres, Redis). The others see the lock held and skip.

```java
// Conceptually (not wired in this single-instance submission):
@Scheduled(fixedRate = 300000)
@SchedulerLock(name = "promotePendingOrders", lockAtMostFor = "4m", lockAtLeastFor = "30s")
@Transactional
public void promotePendingOrders() { ... }
```

- `lockAtMostFor` — safety release if the holder crashes without unlocking.
- `lockAtLeastFor` — hold the lock at least this long to avoid two instances both running if clocks differ slightly.

**Why I named it but didn't build it:** the submission runs a single instance, so it can't double-fire today. Wiring ShedLock would be a one-dependency, one-annotation change, plus a lock table migration. Naming where the complexity goes — without prematurely adding it — is the scope judgment the assignment is probing.

---

## Testing the scheduler

You don't want the timer firing randomly during tests. Two things make it deterministic:

1. The test config parks the interval and initial delay far in the future, so it never auto-fires:

```java
@DynamicPropertySource
static void schedulerProperties(DynamicPropertyRegistry registry) {
    registry.add("orders.scheduler.promote-pending.initial-delay-ms", () -> 3_600_000);
    registry.add("orders.scheduler.promote-pending.fixed-rate-ms", () -> 3_600_000);
}
```

2. The test calls the method **directly** to control exactly when it runs:

```java
@Test
void promotesPendingToProcessing() {
    UUID id = seedPendingOrder();
    scheduler.promotePendingOrders();          // invoke explicitly
    assertThat(repo.findById(id).get().getStatus()).isEqualTo(PROCESSING);
}
```

This is a general testing principle: **make time/triggers explicit in tests** rather than waiting on a real clock.

---

## Likely interview questions

**Q: Why `@Scheduled` instead of a message queue?**
The requirement is time-based ("every 5 minutes"), so a timer is the simplest correct tool. A queue is for event-driven, decoupled, or high-throughput work — over-engineering for one periodic promotion.

**Q: `fixedRate` vs `fixedDelay`?**
`fixedRate` schedules from each run's start (can overlap if slow); `fixedDelay` waits a gap after the previous run finishes. I used `fixedRate` to match "every 5 minutes"; I'd switch to `fixedDelay` if the job were long and overlap was a concern.

**Q: What happens with two app instances?**
The job fires on both → runs twice per tick. Fix with a distributed lock (ShedLock backed by Postgres/Redis) so exactly one instance runs each tick. Not wired here because it's a single-instance submission.

**Q: Why one UPDATE instead of a loop?**
A set-based `UPDATE ... WHERE status = PENDING` is one statement the DB runs against an index, versus N selects + N updates and loading every row into memory. It scales; the loop doesn't.

**Q: How do you test something that runs on a timer?**
Disable the auto-trigger (push the interval/delay far out) and invoke the method directly in the test, asserting the effect. Don't sleep-and-hope.

**Q: Is the job safe against the cancel endpoint?**
Yes — both touch status via atomic SQL with a `WHERE status = PENDING` predicate, so they can't corrupt each other. See [04 — concurrency](./04-concurrency-and-cancel-race.md).
