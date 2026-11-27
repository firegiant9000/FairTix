-- Post-run correctness checks for one load-test event. Read-only.
--   docker exec -i fairtix-lt-postgres psql -U <user> -d <db> -v event_id=<uuid> < loadtest/oversell-check.sql
-- Every "violations" query must return 0 rows.

\echo '== seat states'
SELECT status, count(*) FROM seats WHERE event_id = :'event_id' GROUP BY status ORDER BY status;

\echo '== tickets, orders, queue entries'
SELECT count(*) AS live_tickets FROM tickets
 WHERE event_id = :'event_id' AND status NOT IN ('CANCELLED', 'REFUNDED');
SELECT o.status, count(DISTINCT o.id) AS orders FROM orders o
  JOIN tickets t ON t.order_id = o.id WHERE t.event_id = :'event_id' GROUP BY o.status;
SELECT status, count(*) AS queue_entries FROM queue_entries
 WHERE event_id = :'event_id' GROUP BY status ORDER BY status;

\echo '== violations: a seat with more than one live ticket'
SELECT seat_id, count(*) FROM tickets
 WHERE event_id = :'event_id' AND status NOT IN ('CANCELLED', 'REFUNDED')
 GROUP BY seat_id HAVING count(*) > 1;

\echo '== violations: more live tickets than seats'
SELECT t.n AS tickets, s.n AS seats
  FROM (SELECT count(*) AS n FROM tickets
         WHERE event_id = :'event_id' AND status NOT IN ('CANCELLED', 'REFUNDED')) t,
       (SELECT count(*) AS n FROM seats WHERE event_id = :'event_id') s
 WHERE t.n > s.n;

\echo '== violations: SOLD seat without a live ticket'
SELECT s.id FROM seats s
 WHERE s.event_id = :'event_id' AND s.status = 'SOLD'
   AND NOT EXISTS (SELECT 1 FROM tickets t
                    WHERE t.seat_id = s.id AND t.status NOT IN ('CANCELLED', 'REFUNDED'));

\echo '== violations: live ticket on a seat that is not SOLD'
SELECT t.seat_id, s.status FROM tickets t JOIN seats s ON s.id = t.seat_id
 WHERE t.event_id = :'event_id' AND t.status NOT IN ('CANCELLED', 'REFUNDED')
   AND s.status <> 'SOLD';

\echo '== violations: two live holds on one seat'
SELECT h.seat_id, count(*) FROM seat_holds h JOIN seats s ON s.id = h.seat_id
 WHERE s.event_id = :'event_id' AND h.status IN ('ACTIVE', 'CONFIRMED')
 GROUP BY h.seat_id HAVING count(*) > 1;

\echo '== violations: a user over the per-user cap'
SELECT t.user_id, count(*) FROM tickets t JOIN events e ON e.id = t.event_id
 WHERE t.event_id = :'event_id' AND t.status NOT IN ('CANCELLED', 'REFUNDED')
 GROUP BY t.user_id, e.max_tickets_per_user
HAVING count(*) > e.max_tickets_per_user;

\echo '== violations: a COMPLETED order for this event with fewer live tickets than holds'
SELECT o.id, count(DISTINCT oh.hold_id) AS holds, count(DISTINCT t.id) AS live_tickets
  FROM orders o
  JOIN order_holds oh ON oh.order_id = o.id
  JOIN seat_holds h ON h.id = oh.hold_id
  JOIN seats s ON s.id = h.seat_id AND s.event_id = :'event_id'
  LEFT JOIN tickets t ON t.order_id = o.id AND t.status NOT IN ('CANCELLED', 'REFUNDED')
 WHERE o.status = 'COMPLETED'
 GROUP BY o.id
HAVING count(DISTINCT t.id) < count(DISTINCT oh.hold_id);

\echo '== violations: a ticket holder never admitted from the queue'
SELECT DISTINCT t.user_id FROM tickets t
 WHERE t.event_id = :'event_id' AND t.status NOT IN ('CANCELLED', 'REFUNDED')
   AND NOT EXISTS (SELECT 1 FROM queue_entries q
                    WHERE q.event_id = t.event_id AND q.user_id = t.user_id
                      AND q.status IN ('ADMITTED', 'COMPLETED'));
