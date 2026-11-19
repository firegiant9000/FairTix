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
-- STRIPE_ORDER_BUSY when the purchase-cap lock stayed busy. STRIPE_ORDER_FAILED
-- with error=PaymentNotVerified in its details means checkout answered 402
-- because verification failed: almost always a paid intent whose amount no
-- longer matches the holds (a seat price changed, or a hold was swapped).
-- not_verified = true marks a row with any such attempt. For those, open the
-- intent in Stripe first: if it never succeeded there is nothing to refund.
-- Their listed holds are the ones submitted, which may not be the ones the
-- intent paid for, so holds_still_confirmed = 0 is not enough on its own. A
-- retry with the paid holds can still create the order. Before refunding, also
-- confirm that the user has no ACTIVE or CONFIRMED hold at all for the event
-- (an ACTIVE hold can still be confirmed and checked out with this intent).
-- Take <user_id> from the row's user_id column and replace the two ids:
--   SELECT h.id FROM seat_holds h JOIN seats s ON s.id = h.seat_id
--   WHERE h.owner_id = '<user_id>' AND h.status IN ('ACTIVE', 'CONFIRMED')
--     AND s.event_id = (SELECT s2.event_id FROM seat_holds h2
--                       JOIN seats s2 ON s2.id = h2.seat_id
--                       WHERE h2.id = '<a listed hold id>');
-- Refund only if that returns no rows.
-- error=IntentNotBound (checkout answered 409: the intent was paid for other
-- holds, another payout routing, or has no online_payment_intents row) is
-- marked not_verified too, and so is error=BindingNotChecked (the charge
-- succeeded but checking that binding failed, so it is unknown). Their listed
-- holds may or may not be the ones paid for, and the paid ones may sit on
-- another event. The intent can still buy its own holds, so check the user's
-- ACTIVE or CONFIRMED holds on every event, not just the listed one (drop the
-- event_id condition above). Intents that got an
-- order on a later attempt have a payment_records row (transaction_id is the
-- intent id) and are left out here. Expect no rows.
--
-- Every row, before any refund: a charge already refunded without an order stays
-- listed (until its attempts leave the 14-day window, or for unbound rows until
-- the owner's review is on record), and Stripe keeps a refunded intent "succeeded".
-- So open the charge in Stripe and refund only if amount_refunded is 0 and
-- refunded is false. A second refund after a partial one pays out twice.
--
-- unbound = true rows: none of the hold rules below or above apply; only this
-- paragraph and the refund check above do. They come from STRIPE_ORDER_FAILED with
-- error=PaymentIntentUnbound: checkout answered 409 PAYMENT_NOT_USABLE to a
-- paid intent with no fairtix_user_id and no fairtix_channel metadata, more
-- than 24 hours old. No checkout can ever use that intent, so its holds don't
-- gate a refund and contacting the customer to finish the order doesn't apply.
-- Nothing on the intent proves FairTix online checkout created it (it may be a
-- Dashboard charge, or a box-office intent from before fairtix_channel
-- existed), so these rows are always escalate = true and only the repo owner
-- refunds them, once all of these hold:
--   1. In Stripe, the intent's metadata has neither fairtix_user_id nor
--      fairtix_channel (fairtix_org_id may be present: Connect online intents
--      carry it), and it was created before the owner-binding deploy (#293).
--   2. SELECT 1 FROM box_office_sales
--      WHERE stripe_payment_intent_id = '<payment_intent>';
--      returns no rows.
--   3. The intent's amount_received (in cents) equals one attempt's holds'
--      seat prices (in currency units) times 100. Each attempt records the
--      holds it submitted; list them with:
--        SELECT created_at, substring(details from 'holdIds=\[([^]]*)\]') AS hold_ids
--        FROM audit_logs
--        WHERE action IN ('STRIPE_ORDER_FAILED', 'STRIPE_ORDER_BUSY')
--          AND position('stripe_intent=<payment_intent>,' IN details) > 0
--        ORDER BY created_at;
--      then, per attempt:
--        SELECT SUM(s.price) * 100 FROM seat_holds h JOIN seats s ON s.id = h.seat_id
--        WHERE h.id IN ('<that attempt''s hold ids>');
--      The check passes if any attempt's total matches. Seat prices at hold
--      time are not stored, so a mismatch fails it, even one a later price
--      change might explain.
--   4. The charge has no dispute, open or closed.
-- If any of 1 to 3 fails, don't refund: investigate where the charge came from.
-- user_id is whoever submitted the intent, not a verified owner; a second
-- submitter shows up as a second row for the same intent.
-- Unbound rows don't age out of this check: their attempts stay listed past
-- 14 days until the owner records the outcome, after a full refund or a
-- decision not to refund. A refund webhook alone doesn't clear the row, because
-- a partial refund leaves part of the charge without an order. The checks
-- session is read-only, so run this in a separate, writable session (don't turn
-- off default_transaction_read_only here), with the owner's own user id and no
-- customer details in reason:
--   INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)
--   VALUES (gen_random_uuid(), '<owner user id>',
--           'STRIPE_CHARGE_REVIEWED', 'PAYMENT',
--           'Unbound charge reviewed: stripe_intent=<payment_intent>, outcome=<refunded|kept>, reason=<why>',
--           now());
--
-- Rows that are not unbound: refund only when holds_still_confirmed = 0. While any of the charge's
-- holds is still CONFIRMED, any later checkout request with this intent id (a
-- customer retry, a double submit, a direct API call) can still create the
-- order. Before refunding, check the charge in Stripe for any dispute (open or
-- closed): if there is one, do not refund. A refund on top of a chargeback pays
-- the customer twice; respond to the dispute in the Stripe Dashboard
-- (Payments > Disputes) instead. Checkout rejects an intent that is already
-- refunded or disputed
-- (409 PAYMENT_REFUNDED), but that check runs
-- once, before the order is written: a refund landing during order creation
-- (seconds, with lock retries) still becomes a live order with nothing to flag
-- it. Treat the 409 as defence in depth, not as permission to refund early.
-- Elapsed time is no guide, because each visit to the checkout page extends
-- confirmed holds. NULL means at least one attempt's audit details carry no
-- readable hold ids: do not refund, investigate. busy_only = true means every
-- attempt so far failed on a busy lock.
--
-- escalate = true: any unbound row, whatever its age or holds (see above), or
-- the first failure is more than 7 days old and the charge is still not
-- refundable (some hold CONFIRMED, or unreadable hold ids). For the latter: attempts
-- drop out of this 14-day window one by one, so after day 14 a row describes
-- only its later attempts and can turn "refundable" (0) without being so:
-- never refund an intent that was escalated on the strength of a later 0. Don't
-- wait:
-- escalate to the repo owner, who decides between releasing the holds (the
-- charge then becomes refundable) and contacting the customer to finish the
-- order (Complete Order with this intent can still create it).
SELECT f.payment_intent,
       f.user_id,
       MIN(f.created_at)                         AS first_failed_at,
       MAX(f.created_at)                         AS last_failed_at,
       COUNT(*)                                  AS failed_attempts,
       bool_and(f.action = 'STRIPE_ORDER_BUSY')  AS busy_only,
       bool_or(f.not_verified)                   AS not_verified,
       bool_or(f.unbound)                        AS unbound,
       -- MAX ignores NULL, so one unreadable attempt must make the whole row NULL.
       CASE WHEN COUNT(*) FILTER (WHERE f.holds_still_confirmed IS NULL) > 0 THEN NULL
            ELSE MAX(f.holds_still_confirmed)
       END                                       AS holds_still_confirmed,
       bool_or(f.unbound)
         OR (MIN(f.created_at) < now() - interval '7 days'
             AND (COUNT(*) FILTER (WHERE f.holds_still_confirmed IS NULL) > 0
                  OR MAX(f.holds_still_confirmed) > 0)) AS escalate
FROM (
  SELECT substring(a.details from 'stripe_intent=([^,]+)') AS payment_intent,
         a.user_id,
         a.action,
         a.created_at,
         (a.details LIKE '%error=PaymentNotVerified%'
           OR a.details LIKE '%error=IntentNotBound%'
           OR a.details LIKE '%error=BindingNotChecked%') AS not_verified,
         a.details LIKE '%error=PaymentIntentUnbound%' AS unbound,
         CASE WHEN a.details ~ 'holdIds=\[[^]]*[0-9a-f]{8}-' THEN
           (SELECT COUNT(*)
            FROM seat_holds h
            WHERE h.status = 'CONFIRMED'
              AND h.id::text IN (
                SELECT m.hold[1]
                FROM regexp_matches(
                       substring(a.details from 'holdIds=\[([^]]*)\]'),
                       '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})',
                       'g') AS m(hold)))
         END AS holds_still_confirmed
  FROM audit_logs a
  WHERE a.action IN ('STRIPE_ORDER_FAILED', 'STRIPE_ORDER_BUSY')
    AND (a.created_at > now() - interval '14 days'
         OR a.details LIKE '%error=PaymentIntentUnbound%')
) f
WHERE NOT EXISTS (
  SELECT 1 FROM payment_records p WHERE p.transaction_id = f.payment_intent
)
GROUP BY f.payment_intent, f.user_id
-- An unbound charge is done once the owner has reviewed it (see the comment above).
HAVING NOT (bool_or(f.unbound) AND EXISTS (
  SELECT 1 FROM audit_logs r
  WHERE r.action = 'STRIPE_CHARGE_REVIEWED'
    AND position('stripe_intent=' || f.payment_intent || ',' IN r.details) > 0
))
ORDER BY first_failed_at DESC;

-- name: refunds_on_live_orders
-- Orders whose money Stripe refunded with no refund request behind it. The
-- order is live and its money is gone. Expect no rows. Two sources:
--   REFUND_ON_LIVE_ORDER: the order existed when Stripe's charge.refunded
--     arrived, had no active refund request, and Stripe had refunded more than
--     its completed refund requests add up to (typically a Dashboard refund).
--   STRIPE_REFUND_UNMATCHED: the intent had no payment record yet. Most of these
--     are charges refunded from stripe_charges_without_order and never match an
--     order. One that matches a COMPLETED order is a refund that landed while
--     checkout was creating the order (refunded_before_order = true).
-- Webhook redeliveries repeat audit rows; they are grouped per order.
SELECT o.id                                            AS order_id,
       o.status                                        AS order_status,
       r.payment_intent,
       MAX(r.amount_refunded_cents)                    AS amount_refunded_cents,
       bool_or(r.action = 'STRIPE_REFUND_UNMATCHED')   AS refunded_before_order,
       MIN(r.created_at)                               AS first_seen_at,
       COUNT(*)                                        AS events
FROM (
  SELECT a.action,
         a.created_at,
         substring(a.details from 'stripe_intent=([^,]+)') AS payment_intent,
         substring(a.details from 'amount_refunded_cents=([0-9]+)')::bigint
                                                       AS amount_refunded_cents
  FROM audit_logs a
  WHERE a.action IN ('REFUND_ON_LIVE_ORDER', 'STRIPE_REFUND_UNMATCHED')
    AND a.created_at > now() - interval '14 days'
) r
JOIN payment_records p ON p.transaction_id = r.payment_intent
JOIN orders o ON o.id = p.order_id
WHERE r.action = 'REFUND_ON_LIVE_ORDER' OR o.status = 'COMPLETED'
GROUP BY o.id, o.status, r.payment_intent
ORDER BY first_seen_at DESC;

-- name: sales_not_recorded
-- Online orders whose sale is missing from org_sales_ledger, so the org's rolling
-- 24-hour sales cap under-counts it. Source: SALES_LEDGER_WRITE_FAILED, written when
-- the order committed but the ledger write failed (checkout still answered 201).
-- A resubmit doesn't record the sale, so nothing repairs this on its own. Rows drop
-- out once an ONLINE ledger entry exists for the intent. Expect no rows.
-- org_id is NULL when the org lookup itself failed: take it from the order's event.
SELECT s.order_id,
       s.payment_intent,
       s.org_id,
       s.amount_cents,
       s.failed_at
FROM (
  SELECT a.resource_id                                            AS order_id,
         substring(a.details from 'stripe_intent=([^,]+)')        AS payment_intent,
         NULLIF(substring(a.details from 'orgId=([^,]+)'), 'null') AS org_id,
         substring(a.details from 'amountCents=([0-9]+)')::bigint AS amount_cents,
         a.created_at                                             AS failed_at
  FROM audit_logs a
  WHERE a.action = 'SALES_LEDGER_WRITE_FAILED'
    AND a.created_at > now() - interval '14 days'
) s
WHERE NOT EXISTS (
  SELECT 1 FROM org_sales_ledger l
  WHERE l.channel = 'ONLINE' AND l.source_id = s.payment_intent
)
ORDER BY s.failed_at DESC;
