# How I used AI on this

The brief asked us to lean on AI and be honest about it, so here's the real account.

I was already comfortable with the CRUD side of this — creating, retrieving, listing and cancelling orders — so for those I mostly used Cursor to scaffold the Spring Boot project and write the boilerplate quickly, then read it to make sure it matched how I'd have written it myself.

The two parts that were genuinely new to me were the **scheduled background job** and the **idempotency key**. That's where I leaned on AI the most — to learn the approach, implement it, and then fix the things it got wrong.

## Scheduled job (PENDING → PROCESSING every 5 minutes)

I hadn't written a recurring background job in Spring before, so I asked how to do it and it pointed me at `@Scheduled`. Two things I had to correct:

- Its first version loaded all the pending orders and updated them one by one in a loop. That's a lot of round-trips for something the database can do in a single statement, so I changed it to one set-based update: `UPDATE orders SET status = 'PROCESSING' WHERE status = 'PENDING'`.
- The bigger issue showed up when I thought about the job running at the exact moment someone cancels an order. The AI's cancel code read the order, checked it was PENDING, then saved it as CANCELLED — which can quietly overwrite an order the job just promoted to PROCESSING. I changed cancel to a conditional update (cancel only if it's *still* PENDING), so the two can't overwrite each other.

I verified it with an integration test that seeds a PENDING order, runs the job, and asserts it becomes PROCESSING — plus a two-thread test that fires cancel and the job at the same order and checks exactly one of them wins.

## Idempotency key

This was also a new concept for me. I asked how to stop a double-clicked or retried "create order" from inserting duplicate orders, and it suggested an `Idempotency-Key` header with the keys stored in a table.

The issue: its first version just checked "have I seen this key before?" and then inserted. That still creates two orders if two identical requests arrive at the same instant, because both pass the check before either one saves. I fixed it by putting a UNIQUE constraint on the key in the database and handling the constraint violation — so the database is what actually guarantees one order per key, not my code. I verified it with a test that sends the same key twice and asserts both responses return the same order id.

## Bottom line

For the CRUD parts, AI mostly made me faster. For the scheduled job and the idempotency key — the parts that were new to me — it was genuinely useful for learning the approach, but its first attempts were happy-path and missed what happens when two things run at the same time. I caught those by thinking through the concurrent cases and writing tests that actually exercise them.
