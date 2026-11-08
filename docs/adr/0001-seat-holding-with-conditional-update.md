# ADR-0001: Seat holding with one conditional UPDATE

- Status: accepted
- Date: 2026-11-08

## Context

Two customers must never hold or buy the same seat, even when hundreds of requests for the same seats
arrive at the same moment. A booking request holds several seats, and it must be all or nothing: a
request that cannot get every seat must not leave some of them held.

Seat state (`AVAILABLE`, `HELD`, `SOLD`) is stored in the `event_seats` table, one row per seat per event.
We need a way to move seats from `AVAILABLE` to `HELD` that is correct under concurrency, easy to explain
and fast.

## Options considered

1. **Check in Java, then write.** Read the seats, check that they are `AVAILABLE`, then update them.
   Rejected: between the check and the write another transaction can take the seat (a race). Two
   customers can both pass the check and both "win" the seat.
2. **Pessimistic locking** (`SELECT ... FOR UPDATE` on the seats, then update). Correct, but every
   request takes row locks and holds them while Java code runs; the order in which locks are taken has to be
   consistent or two requests that need the same seats in a different order can deadlock. More round trips
   and more waiting.
3. **Optimistic locking** (`@Version` on each seat entity). Correct, but needs one entity per seat loaded
   into memory (up to 6 per request), and a conflict surfaces as an exception at commit time that has to be
   translated and retried. Heavier code for the same result.
4. **One conditional UPDATE in PostgreSQL** (chosen).
   ```sql
   update event_seats
   set status = 'HELD', order_id = :orderId, hold_expires_at = :expiresAt, version = version + 1
   where event_id = :eventId and id in (:seatIds) and status = 'AVAILABLE'
   ```
   The condition and the write are one atomic statement. If another transaction has just changed a row,
   PostgreSQL waits for it to finish and then re-evaluates the condition on the new version of the row, so
   the row is skipped if it is no longer `AVAILABLE`. The number of updated rows tells us how many seats we
   really got.
5. **Holds in Redis** (e.g. `SET NX` with a TTL). Fast, but it creates two sources of truth: Redis says a
   seat is held, the database says it is available, and the two can disagree after a crash or a failover.
   Every payment and expiry flow would have to keep them in sync. Rejected for correctness, not speed.

## Decision

Seats are held with the single conditional `UPDATE` of option 4, inside the same transaction that creates
the order. If the number of updated rows is smaller than the number of seats requested, the service throws
`SeatsUnavailableException` (HTTP 409). The exception rolls the transaction back, so the order and every
seat that was updated by this request are undone: all or nothing.

The database is the single source of truth. Availability is never checked in Java before the write and holds
are never kept in Redis. The same pattern is used for the later transitions (`HELD -> SOLD` with
`where order_id = :orderId and status = 'HELD'`).

A second, separate rule, "at most N seats per customer per event", needs a check over several rows and
therefore is not covered by the UPDATE. It uses a transaction-scoped advisory lock
(`pg_advisory_xact_lock`) keyed by customer and event: parallel requests of the same customer for the same
event run one after the other, so each sees the seats held by the previous one. Other customers do not wait.

## Consequences

- Correct under any number of concurrent requests; proven by a test that fires 200 threads at 100 seats and
  checks that no seat is in two orders.
- One statement per request, no lock-ordering problems, no retries.
- The failure response cannot say which seat was taken (only "one or more seats are no longer available"),
  which also avoids leaking who holds what.
- Requests that compete for the same rows wait briefly for each other inside PostgreSQL; this is the
  intended behaviour and is short because the transaction only does a few statements.
- Expired holds are not released by this statement; a scheduled job moves them back to `AVAILABLE` (a later
  step).
- The seat table is written with SQL instead of through an entity. The price of this is a little less
  type safety; the benefit is that the rule is visible in one place.
