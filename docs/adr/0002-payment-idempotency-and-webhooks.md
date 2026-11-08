# ADR-0002: Payment idempotency and webhooks

- Status: accepted
- Date: 2026-11-08

## Context

Paying an order involves a payment provider we do not control. Two things go wrong in practice:

1. **Clients retry.** A mobile app times out and sends the same checkout again. We must not create two payments
   (and charge twice).
2. **Providers retry.** A provider delivers the same "payment succeeded" webhook more than once, sometimes in
   parallel, and sometimes long after the customer's hold on the seats has expired. The webhook endpoint is
   public, so anyone can also try to forge one.

The provider is simulated (`payment.fakepay`) but lives behind HTTP and a signed webhook, exactly as a real one
would, so the code is the same shape as production code.

## Decisions

### Checkout is idempotent by `Idempotency-Key`

- The header is required. The pair `(customer_id, key)` is the primary key of `idempotency_keys`.
- First transaction: `insert ... on conflict do nothing` claims the key. Exactly one of several concurrent
  requests gets the row; the others wait on the primary key and then see it exists. The same transaction checks the
  order and creates a `PENDING` payment.
- The provider is called **outside** any database transaction (no connection or lock held during network I/O).
- Third transaction: store the provider id and the full response (status code and body) under the key.
- Same key + same request: the stored response is replayed byte for byte. Same key + a different request (the
  request hash covers the order id): `422`. Same key while the first request is still running: `409`.
- If the provider call fails the payment is marked `FAILED` and the key is **deleted**, so the client can retry
  with the same key. Business errors (404 / 409 for the order) roll the key back with the first transaction, so
  they are not cached.

### The webhook is authenticated by signature, not by a user token

- `X-FakePay-Signature` = hex `HMAC-SHA256(secret, timestamp + "." + rawBody)`, `X-FakePay-Timestamp` in epoch
  seconds. The timestamp is inside the signed text and must be within 5 minutes of our clock, which stops replays
  of old messages.
- The signature is compared in constant time (`MessageDigest.isEqual`).
- It is computed over the raw body string, so the body is parsed only after verification.
- Every verification failure is the same generic `401`.

### The webhook is idempotent by provider payment id

- The payment row is locked (`SELECT ... FOR UPDATE`). A payment that is no longer `PENDING` means a duplicate: it
  changes nothing and still answers `200`, so the provider stops retrying.
- Success: the order row is locked, and if the order is `PENDING` the seats go `HELD -> SOLD` with a conditional
  `UPDATE ... where order_id = :id and status = 'HELD'` (same pattern as holding), the order becomes `PAID` and
  `OrderPaid` is published (delivered after commit). The row count must equal the number of items; anything else is
  a bug and rolls the whole transaction back (the provider will retry).
- Failure: the payment becomes `FAILED`; the order stays `PENDING`, so the customer can pay again until the hold
  ends.

### Edge case: money arrives after the hold expired

If the order is no longer `PENDING` when a success webhook arrives (expired, cancelled, or already paid by another
payment), the seats are not sold, the order is left as it is, the payment is marked `REFUNDED` and a warning is
logged. In this simulation `REFUNDED` is only a status: the real refund call to the provider belongs to Step 12.

A `PENDING` order whose deadline passed a few seconds ago but that the expiry job has not swept yet **is** paid:
its seats are still `HELD` by it and nobody else could take them, so refusing the customer's money would only
hurt.

## Consequences

- Locks are always taken in the order payment, then order; checkout, cancel and the expiry job take only the order.
  No cycle, so no deadlock between them.
- The idempotency table grows; old keys should be purged by a job (not done yet, a candidate for a later step).
- Replaying a stored response also replays a stale `payUrl`; acceptable, because the payment it points to is the
  one that was created.
- The fake provider keeps its state in memory and does not retry webhooks. A real provider would; our endpoint is
  already safe for that.
