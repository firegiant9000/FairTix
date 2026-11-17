# Rollout: purchase cap and queue capacity under concurrency

Covers PRs #271 to #278, merged to `main` on 2026-11-16 (merge commit `626bef19`). Use it the first time this code reaches an environment with real traffic, and keep it for the week after.

There is no such environment yet: the Railway staging in [runbook-staging.md](../runbook-staging.md) was never provisioned, and the AWS lab in [docs/infra](../infra/README.md) is not built. Until one exists, the steps below have nothing to run against.

The SQL lives in [cap-queue-checks.sql](cap-queue-checks.sql). Every query in it runs in CI against the migrated schema (`RolloutChecksIT`), so they stay valid as the schema changes. All of them are read-only.

## What changed for users

| Behaviour | Before | After |
| --- | --- | --- |
| Two overlapping holds or checkouts by one user for a capped event | Both could pass the cap | Run one after the other; the second sees the first |
| A confirmed hold | Stopped counting at the hold gate | Counts until it is bought or released |
| A refunded ticket | Counted at checkout and queue join, not at the hold gate | Frees its slot everywhere |
| Accepting a ticket transfer | No cap check | Refused if it would put the recipient over the cap |
| Stripe checkout over the cap | Refused after the charge | Refused when the PaymentIntent is created, before any charge |
| Overlapping joins to a capped queue | Could exceed `queue_capacity` | Run one at a time per event |
| A request that waits too long for one of these locks | n/a | 409 `REQUEST_IN_PROGRESS`; the locked work is rolled back (Stripe checkout still logs `STRIPE_ORDER_BUSY`, see below). With PR #279 the frontend retries it twice, except on checkout and queue join |

## Settings

| Property | Default | What it bounds |
| --- | --- | --- |
| `fairtix.transaction-lock.wait-ms` | 3000 | Wait for the (event, user) purchase-cap lock: holds, checkout, PaymentIntent creation, transfer acceptance |
| `queue.join-lock-wait-ms` | 500 | Wait for the per-event lock when joining a capped queue |
| `spring.datasource.hikari.maximum-pool-size` | 10 (Hikari default; not set) | Connections per instance; every request waiting on a lock holds one |

Stripe checkout retries order creation 3 times on a busy lock (constant in `PaymentController`), so a customer who has paid can wait up to about 9 seconds before a 409. The frontend does not retry checkout on top of that. When those 3 attempts fail, `PaymentController` writes a `STRIPE_ORDER_BUSY` audit row before answering 409. Any other failure after the charge writes `STRIPE_ORDER_FAILED`. A double submit whose order the other request already created writes neither. The customer's holds stay confirmed, so trying again can still create the order. That is why `stripe_charges_without_order` checks for a payment record instead of counting audit rows.

## Before deploying

1. **PostgreSQL version.** Run `postgres_version`. It must be 11 or later; the project targets 16.
2. **Connection pooler.** If a pooler sits between the app and the database, it must not be in statement mode. Transaction mode and session mode are fine: the locks are transaction-scoped.
3. **Users already over a cap.** Run `users_over_cap`. Each row is a user whose next hold, checkout or transfer acceptance for that event will be refused. The usual cause is a cap lowered after people bought, or confirmed holds placed before this release. Decide per event whether that is intended; nothing needs to be changed for the deploy to be safe.
4. **Queues already over capacity.** Run `queues_over_capacity`. Rows here are not dangerous; new joins to those events will be refused until the queue drains below capacity.

## Deploying

No migration, no new dependency. Roll out as usual. Instances on the old code and the new code can run side by side: the old code takes none of the new locks, so during a rolling deploy the old instances can still let a race through, exactly as before.

## Right after deploying (smoke test)

Do this on the target environment with two test accounts and a test event, not on a real on-sale.

1. Create an event with `max_tickets_per_user = 1` and at least two seats.
2. As one user, send two hold requests for different seats at the same moment (two browser tabs, or two `curl` calls started together). Expect one 201 and one 409 with "Purchase cap".
3. Release the hold, then buy one seat. Try to hold the second seat: expect 409 "Purchase cap".
4. Set `queue_required = true` and `queue_capacity = 1` on a second event. Two different users join at the same moment: expect one success and one 409 "Queue is at capacity".
5. Open `/swagger-ui.html` and check the page loads and an operation expands. swagger-ui is pinned above the version springdoc was built for (PR #275).
6. Run `users_over_cap`, `queues_over_capacity` and `duplicate_queue_positions`: expect no new rows from the test.

## The first week

Daily, or after any on-sale:

| Check | How | Expect | Act when |
| --- | --- | --- | --- |
| Cap invariant | `users_over_cap` | No rows beyond the ones found before deploy | A new row appears: find how (transfer, box office, lowered cap) from `audit_logs` for that user and event |
| Queue invariant | `queues_over_capacity` | No rows | Rows that are not explained by recent hold releases |
| Positions | `duplicate_queue_positions` | No rows | Any row |
| Paid but no order | `stripe_charges_without_order` | No rows | Any row with `holds_still_confirmed = 0` (NULL means no readable hold ids: investigate, don't refund): confirm in Stripe that the intent succeeded, has no order and its charge has no open dispute (if it does, don't refund here: use the dispute process), then refund it. While any hold is still confirmed the customer can still complete the order, so wait for the holds to be released or swept. Checkout rejects an already refunded or disputed intent (409 `PAYMENT_REFUNDED`), but only before the order is written: a refund that lands during order creation still becomes a live order. Treat that check as defence in depth, not permission to refund early. Don't go by elapsed time: each checkout page visit extends the holds. Do not refund from audit rows alone: a later attempt may have fulfilled the order |
| Lock timeouts | Application log: `WARN ... Transaction lock wait timed out on <uri>` | Rare | Sustained on `/queue/join` during an on-sale: see below |
| Pool exhaustion | Application log: Hikari `Connection is not available, request timed out` | Never | Any occurrence; most likely during a burst of queue joins (see below) |
| Paid, no order, no audit row | Application log: `ERROR ... Charge succeeded, order not created, and the audit row could not be written` | Never | Any occurrence: `stripe_charges_without_order` cannot see this charge. Take `stripe_intent` and `holdIds` from the log line. Check Stripe and `payment_records`, and refund only once none of those holds is CONFIRMED in `seat_holds` |

During an on-sale, watch `lock_waiters`, `connections_by_state` and `advisory_locks_held`, polling every 10 to 30 seconds. Run them with the session settings at the top of the SQL file and autocommit on. Short waits are normal. Many sessions waiting for close to the limit, or active connections at 10 per instance, mean the pool is the bottleneck.

### What a burst of joins to a capped queue looks like

Measured with `QueueJoinLoadIT` (opt-in; `./mvnw verify -Dit.test=QueueJoinLoadIT`) on 2026-11-17: one laptop, PostgreSQL 16 and Redis 7 in Docker, one application instance, pool of 10. All joiners for one event released at once.

| Queue | Burst | Join wait | Joined | Refused (409) | Joins/s | p50 | p95 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| capped | 50 | 500 ms | 50 | 0 | 39 | 854 ms | 1243 ms |
| capped | 200 | 500 ms | 200 | 0 | 103 | 1049 ms | 1847 ms |
| capped | 500 | 500 ms | 500 | 0 | 108 | 2370 ms | 4449 ms |
| capped | 200 | 3000 ms | 200 | 0 | 168 | 598 ms | 1120 ms |
| uncapped | 200 | n/a | 200 | 0 | 1087 | 92 ms | 171 ms |

How to read it:

- **The lock sets the ceiling: about 100 joins per second per capped event**, a tenth of an uncapped one with the same pool. The locked part of a join (lookup, count, insert, audit, commit) took roughly 10 ms here and runs one at a time per event, across all instances. More instances do not raise this number. The 3000 ms row is faster because it ran later on a warmed-up JVM, not because of the setting; treat differences under 2x between rows as noise. One run each.
- **On one instance the waiting happens in the pool, not at the lock.** A join holds a pooled connection for its whole transaction, so at most 10 joins are inside the database and at most 9 wait on the lock, about 90 ms of lock wait at worst. That is why no join was refused. Everyone else waited for a connection, which is why p95 grows with the burst.
- **With several instances, waits move to the lock.** Each instance adds up to 9 lock waiters. At about 10 ms per join, roughly 50 waiters (6 instances at pool 10) reach the 500 ms limit, and from there joins start getting 409 `REQUEST_IN_PROGRESS`.
- **On one instance the failure mode is the pool.** A joiner that waits longer than Hikari's `connectionTimeout` (30 s by default) fails with a connection timeout, which reaches the user as a 500. At about 100 joins per second, that is a burst of roughly 3000 joins to one capped event. Holds and checkouts on the same instance wait in the same pool meanwhile.

If joins are slow or failing during an on-sale:

- The per-event ceiling is fixed by the locked section. If an on-sale needs more than about 100 joins per second into one capped queue, the remedy is a code change (for example a capacity counter that does not serialise every join), not configuration.
- More instances spread the waiting and keep holds and checkout responsive, but past about 6 instances at pool 10 they turn waits into 409s. Raising `queue.join-lock-wait-ms` then trades those 409s for longer waits, each holding a connection.
- Raising `spring.datasource.hikari.maximum-pool-size` lets more joins wait in the database instead of in the pool. Check the database's `max_connections` against instances × pool size first.
- The frontend does not retry a 409 `REQUEST_IN_PROGRESS` on queue join, because a retry would add load to the saturated per-event lock. So each 409 here is one refused attempt, and users see it directly. It does not retry a 500 either. (With PR #279 it does retry the per-user purchase-cap paths twice: holds, PaymentIntent creation and transfers.)

Not measured: more than one instance, a real network between app and database, and joins competing with holds and checkout for the pool.

## Rollback

1. Redeploy the previous image. That is the rollback path. If a code revert is unavoidable, revert only #271, #273, #274, #276, #277 and #278 (newest first). **Do not revert #275**: it pins the swagger-ui fix for CVE-2026-116803, and reverting it brings the vulnerable DOMPurify back and fails the Trivy and OWASP gates. #272 is docs only. No schema or data changes to undo; advisory locks disappear with the transactions that held them.
2. Without a redeploy, `fairtix.transaction-lock.wait-ms` and `queue.join-lock-wait-ms` can be raised to trade 409s for longer waits. There is no switch that turns the locks off.
3. After a rollback the old rules return: a refunded ticket blocks checkout and queue join again, and a confirmed hold stops counting at the hold gate. Users who rebought after a refund keep their tickets.

## Known limits

- Releasing a hold puts the user's queue entry back to `ADMITTED` without the capacity check, so a capped queue can briefly exceed its capacity by the number of users who released holds. Accepted by the owner on 2026-11-17: those users were already admitted once, and enforcing the cap here would remove a user from the queue for releasing a seat. Revisit if `queue_capacity` must become a hard ceiling.
- About 100 joins per second per capped event (see the load test above). Accepted by the owner on 2026-11-17 as enough for now. Revisit once real on-sale volumes are known. Raising it means replacing the per-event lock (for example an atomic capacity counter updated with `UPDATE ... WHERE count < capacity`), not tuning.
- Box-office card sales still write `PAYMENT_PROCESSED` in a separate transaction (a second pooled connection). This is deliberate: it keeps a record of a captured card-present charge if the sale rolls back. Many box-office terminals confirming card sales at the same moment could still exhaust the pool; online checkout no longer can.
- Refunds for charges without an order are manual. `stripe_charges_without_order` finds them. A busy row (`STRIPE_ORDER_BUSY`) only needs a refund if the customer never completes the order.
- `admitNextBatch` skips (returns nothing) when another admission for the same event is in progress. The admin "admit" endpoint cannot tell that apart from "nobody to admit".
- Everything was tested on one application instance. Cross-instance behaviour rests on PostgreSQL advisory locks, which `TransactionLockIT` exercises with a second raw connection.
- The swagger-ui pin in `backend/pom.xml` should be removed once a springdoc release ships swagger-ui 5.35.0 or later.
