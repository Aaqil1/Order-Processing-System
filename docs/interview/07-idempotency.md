# 07 — Idempotency

## The concept: what does "idempotent" mean?

An operation is **idempotent** if doing it once or many times produces the same result. Mathematically, `f(f(x)) == f(x)`.

In HTTP:
- `GET`, `PUT`, `DELETE` are idempotent by definition. `GET` reads (no change). `PUT` sets a resource to a value — repeating sets it to the same value. `DELETE` removes it — repeating finds it already gone.
- **`POST` is NOT idempotent.** It *creates* a new resource each time. Two identical `POST /orders` calls create **two orders**.

## The problem: why retries create duplicates

The client genuinely cannot know if a failed `POST` actually succeeded:

- User double-clicks "Place order."
- The request succeeds on the server, but the **response** is lost on the way back (flaky network), so the client/browser **auto-retries**.
- A mobile app retries on timeout.

In every case the server receives the "same" create twice and, naively, makes two orders. The client can't safely retry on its own — so we make the **server** able to recognize a repeat.

## The solution: an idempotency key

The client generates a unique token for **one logical operation** and sends it as a header:

```
POST /api/v1/orders
Idempotency-Key: 7f3c1a90-1234-...
```

The contract:
- **Same logical action → same key** (a retry resends the same key).
- **A genuinely new order → a new key.**

The server stores "I've processed key K → it produced order O." On seeing K again, it returns O instead of creating a new order.

This is exactly how Stripe, PayPal, and other serious APIs make `POST` safe to retry.

---

## How it's implemented here

### 1. A table with a UNIQUE constraint (the real guarantee)

```sql
CREATE TABLE idempotency_keys (
    id         UUID PRIMARY KEY,
    idem_key   VARCHAR(255) NOT NULL UNIQUE,   -- the database enforces "one row per key"
    order_id   UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

The `UNIQUE` constraint is the heart of the design. It is **the database**, not application code, that ultimately guarantees a key maps to at most one order — which is what makes it correct under concurrency.

### 2. The entity

```java
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {
    @Id private UUID id;
    @Column(name = "idem_key", nullable = false, unique = true) private String idemKey;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    // ...
}
```

### 3. The controller reads the header (optional)

```java
public ResponseEntity<OrderResponse> create(
        @Valid @RequestBody CreateOrderRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        UriComponentsBuilder uriBuilder) {
    OrderResponse created = orderService.createOrder(request, idempotencyKey);
    ...
}
```

`required = false` → if no key is sent, create behaves like a normal `POST`. The feature is opt-in.

### 4. The service logic (the interesting part)

```java
@Transactional
public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
    boolean hasKey = StringUtils.hasText(idempotencyKey);

    // (A) Fast path: already seen this key → return the original order, create nothing.
    if (hasKey) {
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByIdemKey(idempotencyKey);
        if (existing.isPresent()) {
            return detailById(existing.get().getOrderId());
        }
    }

    Order order = buildOrder(request);

    try {
        orderRepository.save(order);
        recordHistory(order.getId(), null, OrderStatus.PENDING);
        if (hasKey) {
            idempotencyKeyRepository.save(new IdempotencyKey(UUID.randomUUID(), idempotencyKey, order.getId()));
        }
    } catch (DataIntegrityViolationException e) {
        // (B) Race path: two identical requests arrived at once. Both passed (A) before
        //     either committed; one wins the UNIQUE constraint, the other lands here.
        if (hasKey) {
            return idempotencyKeyRepository.findByIdemKey(idempotencyKey)
                    .map(k -> detailById(k.getOrderId()))
                    .orElseThrow(() -> e);
        }
        throw e;
    }

    return detailById(order.getId());
}
```

### Walking the three scenarios

**Scenario 1 — first request (key K never seen):**
- (A) lookup finds nothing → build and save the order, store `(K → orderId)`. Return the new order.

**Scenario 2 — retry (key K already stored):**
- (A) lookup finds K → immediately return the original order. **No second order created.** ✅

**Scenario 3 — two identical requests at the exact same instant (the subtle one):**
- Both threads run (A) before either has committed → **both see "not found"** → both try to insert.
- The `UNIQUE(idem_key)` constraint lets only **one** insert succeed. The other's commit violates the constraint → `DataIntegrityViolationException`.
- (B) catches it, re-reads the key (now committed by the winner), and returns the **winner's** order.

So even under a perfect collision, you end up with exactly one order. The naive "just check then insert" (only step A) would create two orders in Scenario 3 — the `UNIQUE` constraint + catch is what closes that window. **This is the key insight to state in the interview: the check-first is an optimization; the database constraint is the correctness guarantee.**

---

## Important scoping note (say this)

This dedupes **orders**, not **payments**. It prevents a duplicate *order row*. If this system charged a card, preventing a double *charge* is a harder, separate problem (a `payments` table with `UNIQUE(order_id)`, an idempotency key passed to the payment provider so Stripe/Adyen dedupe the actual charge, idempotent webhook handling, reconciliation). Payment is out of scope here, but knowing the distinction shows depth.

---

## How it's tested

```java
@Test
void idempotencyKeyReturnsSameOrderOnRetry() throws Exception {
    String key = UUID.randomUUID().toString();
    String payload = createOrderJson(1);

    String firstId  = post(payload, key).id();   // create
    String secondId = post(payload, key).id();   // retry with same key

    assertThat(secondId).isEqualTo(firstId);      // same order, no duplicate
}
```

---

## Likely interview questions

**Q: What does idempotent mean, and why does POST need help?**
Idempotent = repeating the operation doesn't change the result beyond the first time. `GET/PUT/DELETE` are naturally idempotent; `POST` creates each time, so a retry duplicates. An idempotency key lets the server recognize and dedupe retries.

**Q: Why a UNIQUE constraint instead of just checking "have I seen this key"?**
The check-then-insert is itself a race: two simultaneous requests both pass the check before either commits, and you get two orders. The `UNIQUE` constraint makes the database reject the second insert, and I catch that and return the original. The DB is the arbiter, not application code. (Same lesson as the cancel race — push correctness into the database.)

**Q: What if the same key is sent with a *different* body?**
Real-world APIs (Stripe) reject a reused key with a mismatched payload (`422`). This implementation returns the original order regardless of body — a reasonable simplification I'd call out and could tighten by hashing and comparing the request.

**Q: Where do you store keys, and how long?**
A dedicated table here. In production you'd add a TTL/cleanup (or store in Redis with expiry) since keys only need to live as long as clients might retry — hours, not forever.

**Q: Is this exactly-once?**
For order *creation*, effectively yes within this DB. Across a network to an external service (payments) you can't get exactly-once; the realistic goal is at-most-once with the provider as the source of truth.
