# L1 contention correctness (L1-min)

Correctness evidence for seat holds and ticket issuance on PostgreSQL 16. It records counts, not latency; performance is L2.

## Invariants

1. **Holds:** at most one `ACTIVE` seat hold per seat, so no seat is held twice.
2. **Tickets:** at most one ticket per seat whose status is not `CANCELLED` or `REFUNDED`. Since V46 the database enforces this with `uk_tickets_seat_active ON tickets (seat_id) WHERE status NOT IN ('CANCELLED', 'REFUNDED')`.

## Setup

| | |
|---|---|
| Database | `postgres:16.10` (Debian) via Testcontainers, Flyway V1–V46 applied to an empty schema, Hibernate `validate` |
| Redis | still the `TestRedisConfig` Mockito mock; the event has `queue_required = false`, so the queue gate is not involved |
| Clients | N = 200 distinct users |
| Seats | M = 50, one section (`FLOOR`), one event, no purchase cap |
| Requests | 1–4 seats each, drawn from the whole section with `java.util.Random(20261019)`; 530 seat requests for 50 seats |
| Pool | Hikari default, `maximumPoolSize = 10`; 9 worker threads in L1-min (see finding 2), 20 since L1-rest with `connectionTimeout = 5 s` |
| Holds limit | `holds.max-active-per-holder = 5` (the production default; the H2 suite uses 2) |
| Schedulers | off (`fairtix.scheduling.enabled = false`), so no expiry sweep runs mid-test |

## Method

Tests: `SeatHoldContentionIT`, `TicketSeatUniquenessIT`, `FlywayPostgresIT`, all run by failsafe in Maven `verify`.

- **Contention (`SeatHoldContentionIT`):**
  - The 200 requests are fixed before any thread starts.
  - Twenty workers (nine in L1-min) park on one `CountDownLatch`, are released together, and then drain the 200 tasks. Each task calls `SeatHoldService.createHold`, which locks seats with `SELECT … FOR UPDATE ORDER BY id`. Nothing bypasses `SeatHoldService`, and there are no retries.
  - A separate thread on its own unpooled connection samples `pg_locks` (`NOT granted`) and `pg_stat_activity` (`wait_event_type = 'Lock'`) every 50 ms and keeps the maxima.
  - Every winner then confirms its holds and checks out through `OrderService.createOrderWithPayment` (simulated payment, `SUCCESS`).
  - The test asserts: successes + `SeatHoldConflictException` = 200, with no other exception class; no seat has more than one `ACTIVE` hold; `ACTIVE` holds = `HELD` seats ≤ 50; and after checkout no seat has more than one active ticket, with active tickets = `SOLD` seats = held seats.
- **Ticket uniqueness (`TicketSeatUniquenessIT`):**
  - (a) Two `VALID` tickets for one seat must raise `DataIntegrityViolationException`. `REFUNDED` + `VALID` and `CANCELLED` + `VALID` must both be allowed, so resale works.
  - (b) Two threads behind one latch check out the same `CONFIRMED` hold through `OrderService`. The test asserts exactly one success and one active ticket.
  - A duplicate maps to **409** `DATA_CONFLICT` through the application's `GlobalExceptionHandler`.

## Results

### Contention

| Run | Where | Successes | Conflicts | Other exceptions | Seats held | Seats with > 1 `ACTIVE` hold | Seats with > 1 active ticket | Max ungranted locks | Max lock waiters | Race (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | local | 22 | 178 | 0 | 43 | **0** | **0** | 4 | 4 | 537 |
| 2 | local | 21 | 179 | 0 | 41 | **0** | **0** | 3 | 3 | 616 |
| 3 | local, full Maven `verify` | 22 | 178 | 0 | 44 | **0** | **0** | 4 | 4 | 542 |
| 4 | CI on `98878fd`, [run 39362555002](https://github.com/firegiant9000/FairTix/actions/runs/39362555002) | 21 | 179 | 0 | 41 | **0** | **0** | 3 | 3 | 635 |

Successes, seats held and wait counts vary between runs because thread scheduling decides which overlapping request wins. The invariants don't vary. The wait maxima show that requests really did queue on row locks.

### Red on the parent, green on the fix

| Commit | What | CI |
|---|---|---|
| `a240f06` | tests only | **red**, as intended: [run 39359370730](https://github.com/firegiant9000/FairTix/actions/runs/39359370730). `twoValidTicketsForOneSeat_areRejected` failed with "Expecting code to raise a throwable": the second `VALID` ticket inserted. |
| `5db7425` | V46 | **green**: [run 39359627735](https://github.com/firegiant9000/FairTix/actions/runs/39359627735) |

**What (b) did.** On the parent, (b) did **not** produce two tickets. One checkout succeeded. The other inserted its ticket and then failed at flush on `Seat.@Version` (`ObjectOptimisticLockingFailureException`), which rolled the duplicate back. The roadmap's expectation that a two-thread checkout "reproduces two tickets before" did not hold. No other service path duplicated either:
- the seat-freeing paths skip `SOLD` seats or also mark the ticket `REFUNDED`;
- box office and comps lock the seat and require `AVAILABLE`;
- transfer reuses the ticket row.

So, per Arlo's FT-2 ruling, the red test is the database invariant (a). The honest claim is "the database now enforces one active ticket per seat", not "a race was fixed". With V46, an overlapping loser in (b) now fails earlier, on the index (`DataIntegrityViolationException`, 409), because Hibernate flushes the ticket `INSERT` before the seat `UPDATE`.

### L1-rest: above the pool-starvation bound

| Commit | Workers (pool 10) | Successes | Conflicts | Other exceptions | Seats held | Race (ms) |
|---|---|---|---|---|---|---|
| `a9fbbf6` (test only) | 20 | 27 | 133 | **40 `CannotCreateTransactionException`** | 43 | 20 680 |
| fix (`logInCurrentTransaction`) | 20 | 20 | 180 | 0 | 43 | 324 |

## Findings (recorded in L1-min; both fixed in L1-rest)

Both are fixed on `fix/l1rest-correctness`:
- finding 1: `GlobalExceptionHandler` maps `OptimisticLockingFailureException` to 409 `CONCURRENT_MODIFICATION`;
- finding 2: the hold, checkout and simulated-payment paths audit through `AuditService.logInCurrentTransaction` (`MANDATORY`, the caller's connection). The Stripe charge record keeps `AuditService.log` (`REQUIRES_NEW`) on purpose, so a captured charge stays audited if the order rolls back; that path takes a second connection once per charge. Other `@Transactional` callers of `AuditService.log` still take a second connection; that is a follow-up.

1. **Optimistic lock → 500.** `GlobalExceptionHandler` has no handler for `ObjectOptimisticLockingFailureException`, so it falls through to the catch-all and returns **500 `INTERNAL_ERROR`**. On the parent, an overlapping duplicate checkout got this. Since V46 the index usually fires first (409), but any other concurrent write to a seat can still hit the version check and get a 500. Belongs in L1-rest.
2. **Connection-pool starvation under concurrent holds.** `createHold` holds one pooled connection for its transaction and needs a second for `AuditService.log` (`REQUIRES_NEW`). With 200 threads on the default pool of 10, all 10 connections waited for a second one. Hikari reported `total=10, active=10, idle=0, waiting=167`, and 197 of 200 calls failed with `CannotCreateTransactionException` after its 30 s `connectionTimeout`. There were still zero oversells. HikariCP's pool-starvation-free bound is pool ≥ Tn × (Cm − 1) + 1, so the test runs `pool − 1` workers. Only calls that pass every check reach the audit write, because conflict losers release their single connection. Production has the same 10-connection pool behind about 200 Tomcat request threads, so about 10 concurrent transactions reaching a `REQUIRES_NEW` write on one instance can stall for 30 s and then fail. During that stall the stuck `createHold` transactions still hold their `FOR UPDATE` seat locks, taken before the audit write, so other requests for those seats block as well. The checkout and refund paths also call `REQUIRES_NEW` audit from inside a transaction. Fix options for L1-rest: write the audit row in the outer transaction or after commit, or size the pool against request concurrency.

## Limitations

- This is a correctness test, not a performance measurement. The timings above come from one developer machine. CI hardware varies, and nothing here supports a throughput or "under load" claim.
- Concurrency is 20 workers (9 in L1-min, bounded by finding 2), so at most 20 transactions contend at once, and only 10 hold a connection. It is not 200-way contention.
- One round only. The roadmap's multi-round run and the no-ordering deadlock variant are L1-rest, so this doc makes no "deadlock-free" claim.
- Redis is a mock, and the queue gate is off for this event.
- Scheduled expiry is off in this test. `CONFIRMED`-hold expiry, including a checkout racing the sweep, is covered separately by `ConfirmedHoldExpiryIT` (L1-rest). The payment-failure paths are not exercised (L1-rest).
