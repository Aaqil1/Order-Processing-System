# Interview prep — deep concept guides

These notes explain every important concept in this project **end to end**: the idea, *why* it's there, the exact code from this repo, a worked example, and the questions an interviewer is likely to ask. They're written for the PeerIslands "code walkthrough + design pattern" pairing round, but they double as a refresher on backend fundamentals.

Read them in order if you want the full story; jump to a file if you just want to drill one topic.

| # | Topic | What it answers |
|---|---|---|
| 01 | [Architecture & request flow](./01-architecture-and-request-flow.md) | What are the layers, why, and what happens on a single request from HTTP to DB and back |
| 02 | [Design patterns](./02-design-patterns.md) | Every pattern used here, with the concept and the code that proves it |
| 03 | [State machine](./03-state-machine.md) | What a finite state machine is, why orders need one, and how to extend it live |
| 04 | [Concurrency & the cancel race](./04-concurrency-and-cancel-race.md) | Race conditions, read-then-write bugs, conditional updates, optimistic locking |
| 05 | [Transactions & JPA](./05-transactions-and-jpa.md) | `@Transactional`, dirty checking, cascade, lazy loading, N+1, `JOIN FETCH` |
| 06 | [Scheduling / background job](./06-scheduling-background-job.md) | `@Scheduled`, set-based updates, running on multiple instances (ShedLock) |
| 07 | [Idempotency](./07-idempotency.md) | Why retries create duplicates and how the idempotency key + UNIQUE constraint fix it |
| 08 | [Validation & error handling](./08-validation-and-error-handling.md) | Bean Validation, `@RestControllerAdvice`, one consistent error shape |
| 09 | [Testing strategy](./09-testing-strategy.md) | Unit vs integration vs concurrency, Testcontainers, the singleton-container pattern |
| 10 | [Mock interview Q&A + live extensions](./10-mock-interview-qa-and-extensions.md) | 25 questions with model answers, and 6 live-coding drills with full diffs |

## How to use these the night before

1. Read 01 → 09 once, slowly, with the repo open beside you.
2. Do the 6 extension drills in file 10 on your own machine — actually type them.
3. Re-read file 10's Q&A out loud. If you can answer in 2–3 sentences without looking, you're ready.

## The 60-second summary (memorize this)

> It's a layered Spring Boot monolith — controller, service, repository — over PostgreSQL. Five REST operations, a centralized **state machine** for status changes, and a **scheduled job** that promotes PENDING orders to PROCESSING with one set-based `UPDATE`. The interesting part is **cancel**: it races the scheduler, so instead of read-then-write I use a single atomic conditional update (`UPDATE ... WHERE status = 'PENDING'`), backed by an `@Version` optimistic lock, and a two-thread test proves exactly one side wins. Schema is owned by Flyway, errors go through one `@RestControllerAdvice`, and integration tests run against a real Postgres via Testcontainers.
