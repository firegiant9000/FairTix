-- A seat may have only one *active* ticket. Refunded and cancelled tickets keep
-- their row (and seat_id) after the seat is freed, so a plain unique index would
-- block resale; the partial index lets them accumulate. Covers every ticket kind:
-- a comp occupies a seat too. Same pattern as uk_event_hold_active_seat (V41).
CREATE UNIQUE INDEX uk_tickets_seat_active ON tickets (seat_id)
  WHERE status NOT IN ('CANCELLED', 'REFUNDED');
