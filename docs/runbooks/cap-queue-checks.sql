-- Checks for the purchase-cap and queue-capacity rollout (PRs #271-#278).
-- See cap-queue-rollout.md for when to run each one and what a result means.
--
-- Read-only. Every query is executed against the migrated schema by
-- backend/src/test/java/com/fairtix/support/RolloutChecksIT.java, so a schema
-- change that breaks one fails the build. Keep the "-- name:" lines: the test
-- splits the file on them, and ignores everything above the first one.
--
-- Run these with autocommit on, after the session settings below. They stop a
-- slow query from running on during an on-sale and refuse any write, and
-- autocommit stops a GUI client from leaving the session idle in a transaction
-- that holds locks on tickets, seat_holds or queue_entries. Prefer a read
-- replica for users_over_cap and queues_over_capacity during an on-sale.

SET statement_timeout = '5s';
SET default_transaction_read_only = on;

-- name: postgres_version
-- Needs 11 or later (hashtextextended). Expect 16.x.
SHOW server_version;

-- name: users_over_cap
-- Users whose active tickets plus live holds exceed the event's cap. Expect no
-- rows. Before deploy, any row is a user whose next hold or checkout for that
-- event will be refused; after deploy, a row means the cap was bypassed (or the
-- organizer lowered the cap).
SELECT e.id                    AS event_id,
       c.user_id,
       e.max_tickets_per_user  AS cap,
       SUM(c.tickets)          AS active_tickets,
       SUM(c.holds)            AS live_holds
FROM events e
JOIN (
  SELECT t.event_id, t.user_id, COUNT(*) AS tickets, 0 AS holds
  FROM tickets t
  WHERE t.status NOT IN ('CANCELLED', 'REFUNDED')
  GROUP BY t.event_id, t.user_id
  UNION ALL
  SELECT s.event_id, h.owner_id, 0, COUNT(*)
  FROM seat_holds h
  JOIN seats s ON s.id = h.seat_id
  WHERE h.status IN ('ACTIVE', 'CONFIRMED')
  GROUP BY s.event_id, h.owner_id
) c ON c.event_id = e.id
WHERE e.max_tickets_per_user IS NOT NULL
GROUP BY e.id, c.user_id, e.max_tickets_per_user
HAVING SUM(c.tickets) + SUM(c.holds) > e.max_tickets_per_user
ORDER BY e.id, c.user_id;

-- name: queues_over_capacity
-- Capped queues with more live (WAITING + ADMITTED) entries than the capacity.
-- Expect no rows. Known exception: releasing a hold puts a COMPLETED entry back
-- to ADMITTED without the capacity check, so a small overshoot right after
-- hold releases is the open issue, not a regression.
SELECT e.id            AS event_id,
       e.queue_capacity,
       COUNT(*)        AS live_entries
FROM events e
JOIN queue_entries q ON q.event_id = e.id
WHERE e.queue_capacity IS NOT NULL
  AND q.status IN ('WAITING', 'ADMITTED')
GROUP BY e.id, e.queue_capacity
HAVING COUNT(*) > e.queue_capacity
ORDER BY e.id;

-- name: duplicate_queue_positions
-- Two entries of one event with the same position. Expect no rows; the position
-- counter is unique per event even after Redis loses it (PR #276).
SELECT event_id, position, COUNT(*) AS entries
FROM queue_entries
GROUP BY event_id, position
HAVING COUNT(*) > 1
ORDER BY event_id, position;

-- name: lock_waiters
-- Sessions waiting on any lock right now, longest first. A handful of short
-- waits during an on-sale is expected; waits near the limits (500 ms queue
-- join, 3 s purchase cap) or many at once mean contention.
SELECT pid,
       wait_event_type,
       wait_event,
       now() - query_start AS waiting_for,
       left(query, 120)    AS query
FROM pg_stat_activity
WHERE wait_event_type = 'Lock'
  AND datname = current_database()
ORDER BY query_start;

-- name: connections_by_state
-- Database connections to this database by state. The actuator exposes health
-- only, so this is the closest view of the Hikari pool from outside: with the
-- default pool of 10 per instance, "active" plus "idle in transaction" near
-- 10 x instances during a burst means the pool is saturated.
SELECT state, COUNT(*) AS connections
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
GROUP BY state
ORDER BY connections DESC;

-- name: advisory_locks_held
-- Transaction-level advisory locks held or awaited now. They are released at
-- commit, so this should be near zero outside a burst; a count that stays up
-- points at a long-running transaction holding one. Reading pg_locks briefly
-- takes every lock-manager partition lock, so poll it every 10 to 30 seconds at
-- most during an on-sale, not in a tight loop.
SELECT granted, COUNT(*) AS locks
FROM pg_locks
WHERE locktype = 'advisory'
GROUP BY granted;

-- name: stripe_charges_without_order
-- PaymentIntents that succeeded at Stripe but never got an order, last 14 days.
-- Checkout writes STRIPE_ORDER_FAILED when the order failed, and
-- STRIPE_ORDER_BUSY when the purchase-cap lock stayed busy. Intents that got an
-- order on a later attempt have a payment_records row (transaction_id is the
-- intent id) and are left out here. Expect no rows.
--
-- Refund a row only when holds_still_confirmed = 0. While any of the charge's
-- holds is still CONFIRMED, checkout with that intent can still create the
-- order, even after a refund: verification does not look at refunds. Elapsed
-- time is no guide, because each visit to the checkout page extends confirmed
-- holds. busy_only = true means every attempt so far failed on a busy lock.
SELECT f.payment_intent,
       f.user_id,
       MIN(f.created_at)                         AS first_failed_at,
       MAX(f.created_at)                         AS last_failed_at,
       COUNT(*)                                  AS failed_attempts,
       bool_and(f.action = 'STRIPE_ORDER_BUSY')  AS busy_only,
       MAX(f.holds_still_confirmed)              AS holds_still_confirmed
FROM (
  SELECT substring(a.details from 'stripe_intent=([^,]+)') AS payment_intent,
         a.user_id,
         a.action,
         a.created_at,
         (SELECT COUNT(*)
          FROM seat_holds h
          WHERE h.status = 'CONFIRMED'
            AND h.id::text IN (
              SELECT m.hold[1]
              FROM regexp_matches(
                     coalesce(substring(a.details from 'holdIds=\[([^]]*)\]'), ''),
                     '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})',
                     'g') AS m(hold))) AS holds_still_confirmed
  FROM audit_logs a
  WHERE a.action IN ('STRIPE_ORDER_FAILED', 'STRIPE_ORDER_BUSY')
    AND a.created_at > now() - interval '14 days'
) f
WHERE NOT EXISTS (
  SELECT 1 FROM payment_records p WHERE p.transaction_id = f.payment_intent
)
GROUP BY f.payment_intent, f.user_id
ORDER BY first_failed_at DESC;
