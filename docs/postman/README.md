# Postman — test the API end to end

Two ways to use this:

- **Import the collection** and run it (auto-captures the order id between requests).
- **Copy-paste** any request from the [reference below](#copy-paste-reference) into a new Postman request.

---

## A. Import & run the collection (recommended)

1. Start the app: `docker compose up --build` (API on `http://localhost:8080`).
2. In Postman: **Import** → **File** → choose
   [`Order-Processing-System.postman_collection.json`](./Order-Processing-System.postman_collection.json).
3. The collection has a variable **`baseUrl`** = `http://localhost:8080`. Change it only if your app runs elsewhere (Collection → **Variables** tab).
4. Run it one of two ways:
   - **Click through in order.** Run a **Create** request first — it saves the new id into the `{{orderId}}` variable, so the Retrieve / Update / Cancel requests just work.
   - **Collection Runner.** Click the collection → **Run** → **Run Order Processing System**. It executes all 22 requests top-to-bottom, capturing ids automatically, and shows pass/fail for each (every request has test assertions).

### How the variables work

| Variable | Set by | Used by |
|---|---|---|
| `baseUrl` | you (default `http://localhost:8080`) | every request |
| `orderId` | the **Create** / **Setup** requests (test script) | Retrieve, Update Status, Cancel |
| `idempotencyKey` | the first Idempotency request (pre-request script generates a GUID) | both Idempotency requests |
| `idemOrderId` | first Idempotency request | the retry's assertion |

No manual copying of ids needed — the test scripts do it. If you run a single request like *Get order by id* on its own, just run a *Create* first so `{{orderId}}` is populated.

---

## B. Copy-paste reference

Every request below: **method**, **URL**, **headers**, **body**. `baseUrl` = `http://localhost:8080`. Replace `:id` with a real order id (the one returned by Create).

### 1. Create order — `201`

```
POST  {{baseUrl}}/api/v1/orders
Header: Content-Type: application/json
Header (optional): Idempotency-Key: <any-unique-string>
```
```json
{
  "customerId": "11111111-1111-1111-1111-111111111111",
  "items": [
    { "productId": "22222222-2222-2222-2222-222222222222", "quantity": 2, "unitPrice": "19.99" },
    { "productId": "33333333-3333-3333-3333-333333333333", "quantity": 1, "unitPrice": "5.00" }
  ]
}
```

### 1b. Create — empty items — `400`

```
POST  {{baseUrl}}/api/v1/orders
Header: Content-Type: application/json
```
```json
{ "customerId": "11111111-1111-1111-1111-111111111111", "items": [] }
```

### 1c. Create — quantity 0 — `400`

```
POST  {{baseUrl}}/api/v1/orders
Header: Content-Type: application/json
```
```json
{
  "customerId": "11111111-1111-1111-1111-111111111111",
  "items": [ { "productId": "22222222-2222-2222-2222-222222222222", "quantity": 0, "unitPrice": "5.00" } ]
}
```

### 2. Get order by id — `200`

```
GET  {{baseUrl}}/api/v1/orders/:id
```

### 2b. Get unknown id — `404`

```
GET  {{baseUrl}}/api/v1/orders/99999999-9999-9999-9999-999999999999
```

### 3. List orders — `200`

```
GET  {{baseUrl}}/api/v1/orders?page=0&size=20      (all, paginated)
GET  {{baseUrl}}/api/v1/orders?status=PENDING       (filter by status)
GET  {{baseUrl}}/api/v1/orders?status=DELIVERED      (empty result is still 200)
GET  {{baseUrl}}/api/v1/orders?page=0&size=1         (pagination)
```

Valid `status` values: `PENDING`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `CANCELLED`.

### 4. Update status — `200` (legal) / `409` (illegal)

```
PATCH  {{baseUrl}}/api/v1/orders/:id/status
Header: Content-Type: application/json
```
```json
{ "status": "PROCESSING" }
```

Legal path: `PENDING → PROCESSING → SHIPPED → DELIVERED`. Body `status` is one of the same enum values. Anything off the legal path (e.g. `PENDING → SHIPPED`, or moving a `DELIVERED`/`CANCELLED` order) returns `409`.

### 5. Cancel order — `200` (from PENDING) / `409` (otherwise)

```
POST  {{baseUrl}}/api/v1/orders/:id/cancel
```
No body. Works only while the order is `PENDING`; a second cancel, or cancelling a `PROCESSING`/`SHIPPED`/`DELIVERED` order, returns `409`.

### 6. Idempotency — same key returns the same order

```
POST  {{baseUrl}}/api/v1/orders
Header: Content-Type: application/json
Header: Idempotency-Key: 7f3c1a90-0000-0000-0000-000000000001
```
```json
{
  "customerId": "11111111-1111-1111-1111-111111111111",
  "items": [ { "productId": "22222222-2222-2222-2222-222222222222", "quantity": 1, "unitPrice": "1.00" } ]
}
```
Send this **twice with the same `Idempotency-Key`** → both responses have the **same `id`** (no duplicate order). Use a new key value to create a genuinely new order.

---

## Background job (auto PENDING → PROCESSING)

There's no endpoint to trigger this — it runs on a timer. To see it in Postman:

1. Start with a short interval:
   ```bash
   ORDERS_PROMOTE_FIXED_RATE_MS=20000 docker compose up --build
   ```
2. **Create** an order (it's `PENDING`).
3. Wait ~25 seconds, then **Get order by id** — its `status` is now `PROCESSING`.

(Default interval is 5 minutes; the env var above just speeds it up for testing.)

---

See [`../TESTING.md`](../TESTING.md) for the equivalent `curl`-based walkthrough and direct database verification.
