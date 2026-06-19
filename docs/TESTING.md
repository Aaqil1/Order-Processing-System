# Manual end-to-end testing guide

A copy-paste script to exercise **every feature** of the Order Processing System by hand, locally, using Docker + `curl`. Run the blocks top to bottom; each case lists the command and what a **pass** looks like.

> **Using Postman instead of curl?** Import [`postman/Order-Processing-System.postman_collection.json`](./postman/) — it has all the same scenarios as ready-to-run requests (and auto-captures the order id between calls). See [`postman/README.md`](./postman/README.md).

> Prefer automation? `./mvnw test` runs all 23 tests (unit + integration + concurrency) against a real Postgres and proves everything below. This guide is for poking it by hand.

---

## 0. Prerequisites

- **Docker Desktop running** (`docker ps` works).
- Ports **8080** (app) and **5432** (Postgres) free.
- `curl` (preinstalled on macOS). `jq` is optional but makes output readable: `brew install jq`.

---

## 1. Start the stack with Docker

From the project root:

```bash
docker compose up --build
```

This builds the app image and starts **app** (`:8080`) + **Postgres** (`:5432`). Leave it running and open a **second terminal** for the test commands below.

> First build downloads dependencies and can take a few minutes. Wait for the log line `Started OrderProcessingApplication`.

---

## 2. Smoke test — is it up?

```bash
curl -s http://localhost:8080/actuator/health
```

**Pass:** `{"status":"UP", ... "db":{"status":"UP", ...}}`

Browsable API docs (optional): open **http://localhost:8080/swagger-ui.html** — you can run every endpoint from there too.

---

## 3. One-time setup for the test session

Run this in your test terminal so the later commands are short:

```bash
BASE=http://localhost:8080/api/v1/orders

# helper: create an order and print its id
new_order() {
  curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
    -d '{"customerId":"11111111-1111-1111-1111-111111111111","items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":1,"unitPrice":"9.99"}]}' \
    | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//'
}
```

---

## 4. Feature test cases

### 4.1 Create an order (with multiple items)

```bash
curl -i -X POST "$BASE" -H 'Content-Type: application/json' -d '{
  "customerId": "11111111-1111-1111-1111-111111111111",
  "items": [
    {"productId": "22222222-2222-2222-2222-222222222222", "quantity": 2, "unitPrice": "19.99"},
    {"productId": "33333333-3333-3333-3333-333333333333", "quantity": 1, "unitPrice": "5.00"}
  ]
}'
```

**Pass:** `HTTP/1.1 201`, a `Location:` header, body has `"status":"PENDING"`, `"totalAmount":44.98`, **2 items**, and **1 history** entry (`null → PENDING`).

**Negative — empty items (expect `400`):**

```bash
curl -i -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111","items":[]}'
```

**Pass:** `HTTP/1.1 400`, body `"message":"Validation failed"`, `fieldErrors[].field = "items"`.

**Negative — quantity 0 (expect `400`):**

```bash
curl -i -X POST "$BASE" -H 'Content-Type: application/json' \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111","items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":0,"unitPrice":"5.00"}]}'
```

**Pass:** `HTTP/1.1 400` (quantity must be > 0).

---

### 4.2 Retrieve order details by ID

```bash
ID=$(new_order); echo "ID=$ID"
curl -s "$BASE/$ID" | jq          # drop "| jq" if you don't have jq
```

**Pass:** `200`, body has matching `id`, `items`, and a `history` array.

**Negative — unknown id (expect `404`):**

```bash
curl -i "$BASE/99999999-9999-9999-9999-999999999999"
```

**Pass:** `HTTP/1.1 404`, body `"status":404`, `"error":"Not Found"`.

---

### 4.3 List orders (optionally filtered by status)

```bash
# create a couple so the list isn't empty
new_order >/dev/null; new_order >/dev/null

curl -s "$BASE?page=0&size=20" | jq          # all, newest first, paginated
curl -s "$BASE?status=PENDING" | jq          # filter by status
curl -s "$BASE?status=DELIVERED" | jq        # a status with no rows
curl -s "$BASE?page=0&size=1" | jq           # tiny page to see pagination
```

**Pass:**
- All/filtered return `200` with a `content` array and `page/size/totalElements/totalPages`.
- `status=DELIVERED` (when none exist) → `"content":[]`, `"totalElements":0` (still `200`, not 404).
- `size=1` → `content` has at most 1 element, `"size":1`.

---

### 4.4 Update order status (state machine)

```bash
ID=$(new_order); echo "ID=$ID"

# legal forward path → each returns 200
curl -i -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"PROCESSING"}'
curl -i -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"SHIPPED"}'
curl -i -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"DELIVERED"}'
```

**Pass:** three `200`s; final `GET "$BASE/$ID"` shows `"status":"DELIVERED"` and **4 history rows**.

**Negative — illegal skip `PENDING → SHIPPED` (expect `409`):**

```bash
NEW=$(new_order)
curl -i -X PATCH "$BASE/$NEW/status" -H 'Content-Type: application/json' -d '{"status":"SHIPPED"}'
```

**Pass:** `HTTP/1.1 409`, `"message":"Illegal status transition: PENDING -> SHIPPED"`.

**Negative — move a terminal order (expect `409`):**

```bash
# $ID is DELIVERED from above
curl -i -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"PROCESSING"}'
```

**Pass:** `HTTP/1.1 409`.

---

### 4.5 Cancel an order (only while PENDING)

```bash
ID=$(new_order); echo "ID=$ID"

# cancel a PENDING order → 200, becomes CANCELLED
curl -i -X POST "$BASE/$ID/cancel"

# cancel again → 409 (no longer PENDING)
curl -i -X POST "$BASE/$ID/cancel"
```

**Pass:** first `200` with `"status":"CANCELLED"`; second `409` with `"... cannot be cancelled from status CANCELLED"`.

**Negative — cancel a non-PENDING order (expect `409`):**

```bash
ID=$(new_order)
curl -s -o /dev/null -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"PROCESSING"}'
curl -i -X POST "$BASE/$ID/cancel"
```

**Pass:** `HTTP/1.1 409` (`... cannot be cancelled from status PROCESSING`).

---

### 4.6 Background job (auto PENDING → PROCESSING)

The job runs every **5 minutes** by default. Two ways to test it:

**Option A — fast interval (recommended).** Stop the stack (`Ctrl+C`), then start it with a 20-second interval:

```bash
ORDERS_PROMOTE_FIXED_RATE_MS=20000 docker compose up --build
```

Then in the test terminal:

```bash
ID=$(new_order); echo "ID=$ID"
curl -s "$BASE/$ID" | grep -o '"status":"[^"]*"'   # PENDING
sleep 25
curl -s "$BASE/$ID" | grep -o '"status":"[^"]*"'   # PROCESSING
```

**Pass:** status changes from `PENDING` to `PROCESSING` on its own. In the app logs you'll see:
`Promoted 1 order(s) PENDING -> PROCESSING`.

**Option B — default 5 minutes.** With a plain `docker compose up`, create a PENDING order, then re-check after 5 minutes:

```bash
ID=$(new_order)
# ... wait ~5 minutes ...
curl -s "$BASE/$ID" | grep -o '"status":"[^"]*"'   # PROCESSING
```

> The override only changes the interval; the logic is identical. Don't leave a tiny interval running for "real" use — it's purely a testing convenience.

---

### 4.7 Idempotency key (safe retries, no duplicate orders)

```bash
KEY=$(uuidgen)
PAYLOAD='{"customerId":"11111111-1111-1111-1111-111111111111","items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":1,"unitPrice":"1.00"}]}'

A=$(curl -s -X POST "$BASE" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$PAYLOAD" | grep -o '"id":"[^"]*"' | head -1)
B=$(curl -s -X POST "$BASE" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$PAYLOAD" | grep -o '"id":"[^"]*"' | head -1)
echo "first =$A"
echo "second=$B"
```

**Pass:** `first` and `second` are the **same id** — the retry returned the original order instead of creating a duplicate. (Send a different `Idempotency-Key` and you get a new order.)

---

## 5. Verify directly in the database (optional but convincing)

Open a psql shell inside the running Postgres container:

```bash
docker compose exec db psql -U orders -d orders
```

Then run:

```sql
SELECT id, status, total_amount FROM orders ORDER BY created_at DESC LIMIT 5;
SELECT order_id, from_status, to_status, changed_at FROM order_status_history ORDER BY changed_at DESC LIMIT 10;
SELECT idem_key, order_id FROM idempotency_keys;        -- one row per idempotency key
SELECT order_id, product_id, quantity, unit_price FROM order_items LIMIT 10;
\q
```

**Pass:** orders/items/history rows match what the API returned; each idempotency key maps to exactly one order.

---

## 6. Full lifecycle in one go (smoke walkthrough)

```bash
ID=$(new_order); echo "order $ID created (PENDING)"
curl -s -o /dev/null -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"PROCESSING"}'
curl -s -o /dev/null -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"SHIPPED"}'
curl -s -o /dev/null -X PATCH "$BASE/$ID/status" -H 'Content-Type: application/json' -d '{"status":"DELIVERED"}'
curl -s "$BASE/$ID" | jq '{status, history: [.history[].toStatus]}'
```

**Pass:** `{"status":"DELIVERED","history":["PENDING","PROCESSING","SHIPPED","DELIVERED"]}`

---

## 7. Tear down

```bash
docker compose down       # stop app + db (keeps the data volume)
docker compose down -v    # also delete the database volume (fresh start next time)
```

---

## 8. Results checklist

| # | Feature | Command sec. | Expected |
|---|---|---|---|
| 1 | Create order (multi-item) | 4.1 | `201`, total computed, items + 1 history |
| 2 | Create validation | 4.1 | `400` on empty items / quantity 0 |
| 3 | Retrieve by id | 4.2 | `200` with items + history |
| 4 | Retrieve unknown | 4.2 | `404` |
| 5 | List all / filter / paginate | 4.3 | `200`, envelope, filter works, empty = `200` |
| 6 | Update status (legal path) | 4.4 | `200` ×3, ends `DELIVERED` |
| 7 | Update status (illegal) | 4.4 | `409` |
| 8 | Cancel PENDING | 4.5 | `200` → `CANCELLED` |
| 9 | Cancel non-PENDING / repeat | 4.5 | `409` |
| 10 | Background job | 4.6 | `PENDING` → `PROCESSING` automatically |
| 11 | Idempotency | 4.7 | same key → same order id |
| 12 | DB persistence | 5 | rows match API responses |

---

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| `Port 8080 was already in use` | Another app/container is using it: `docker compose down`, or `lsof -nP -iTCP:8080 -sTCP:LISTEN` then stop that process. |
| `curl: (7) Failed to connect to localhost:8080` | App not up yet — wait for `Started OrderProcessingApplication`, or check `docker compose logs app`. |
| Health shows `db` down | Postgres still starting; `docker compose ps` and wait for `db` healthy. |
| Background job didn't promote | Confirm you started with `ORDERS_PROMOTE_FIXED_RATE_MS=20000` and waited longer than the interval; check `docker compose logs app | grep Promoted`. |
| Want a clean slate | `docker compose down -v` wipes the database volume. |
