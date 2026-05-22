-- Extra demo events for the live presentation. Each event is chosen to
-- demonstrate a specific FairTix capability:
--
--   * Taylor Swift   - queue_required=true, max_tickets_per_user=2
--   * Beyonce        - most seats pre-BOOKED to show scarcity visuals
--   * Coachella      - max_tickets_per_user=1 (strict anti-scalping cap)
--   * Comedy Cellar  - small venue (24 seats), NYC location for /near-me
--
-- Idempotent: safe to re-run; uses ON CONFLICT for venues and skips event
-- inserts if titles already exist.

DO $$
DECLARE
  v_organizer_id UUID;
  v_venue_msg_id UUID;  -- Madison Square Garden
  v_venue_sof_id UUID;  -- SoFi Stadium
  v_venue_emp_id UUID;  -- Empire Polo Club
  v_venue_cmc_id UUID;  -- Comedy Cellar
  v_event_swift_id UUID;
  v_event_bey_id   UUID;
  v_event_coach_id UUID;
  v_event_com_id   UUID;
BEGIN

  -- Organizer: prefer admin, else first user
  SELECT id INTO v_organizer_id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1;
  IF v_organizer_id IS NULL THEN
    SELECT id INTO v_organizer_id FROM users ORDER BY id LIMIT 1;
  END IF;
  IF v_organizer_id IS NULL THEN
    RAISE NOTICE 'V29: no users in DB yet, skipping demo event seed (run again after seeding users)';
    RETURN;
  END IF;

  -- =========================================================
  -- Venues
  -- =========================================================
  INSERT INTO venues (id, name, address, city, country, capacity, latitude, longitude)
  VALUES (gen_random_uuid(), 'Madison Square Garden', '4 Pennsylvania Plaza', 'New York', 'US', 20789, 40.750504, -73.993439)
  ON CONFLICT (name) DO UPDATE SET latitude=EXCLUDED.latitude, longitude=EXCLUDED.longitude
  RETURNING id INTO v_venue_msg_id;
  IF v_venue_msg_id IS NULL THEN SELECT id INTO v_venue_msg_id FROM venues WHERE name='Madison Square Garden'; END IF;

  INSERT INTO venues (id, name, address, city, country, capacity, latitude, longitude)
  VALUES (gen_random_uuid(), 'SoFi Stadium', '1001 S Stadium Dr', 'Inglewood', 'US', 70240, 33.953587, -118.339695)
  ON CONFLICT (name) DO UPDATE SET latitude=EXCLUDED.latitude, longitude=EXCLUDED.longitude
  RETURNING id INTO v_venue_sof_id;
  IF v_venue_sof_id IS NULL THEN SELECT id INTO v_venue_sof_id FROM venues WHERE name='SoFi Stadium'; END IF;

  INSERT INTO venues (id, name, address, city, country, capacity, latitude, longitude)
  VALUES (gen_random_uuid(), 'Empire Polo Club', '81-800 Avenue 51', 'Indio', 'US', 125000, 33.681090, -116.237480)
  ON CONFLICT (name) DO UPDATE SET latitude=EXCLUDED.latitude, longitude=EXCLUDED.longitude
  RETURNING id INTO v_venue_emp_id;
  IF v_venue_emp_id IS NULL THEN SELECT id INTO v_venue_emp_id FROM venues WHERE name='Empire Polo Club'; END IF;

  INSERT INTO venues (id, name, address, city, country, capacity, latitude, longitude)
  VALUES (gen_random_uuid(), 'Comedy Cellar', '117 MacDougal St', 'New York', 'US', 120, 40.730320, -74.000610)
  ON CONFLICT (name) DO UPDATE SET latitude=EXCLUDED.latitude, longitude=EXCLUDED.longitude
  RETURNING id INTO v_venue_cmc_id;
  IF v_venue_cmc_id IS NULL THEN SELECT id INTO v_venue_cmc_id FROM venues WHERE name='Comedy Cellar'; END IF;

  -- =========================================================
  -- Event 1: Taylor Swift - Eras Tour Finale (queue gated)
  --   Demonstrates: queue admission system, low purchase cap
  -- =========================================================
  IF NOT EXISTS (SELECT 1 FROM events WHERE title = 'Taylor Swift — Eras Tour Finale') THEN
    v_event_swift_id := gen_random_uuid();
    INSERT INTO events (id, title, start_time, venue_id, organizer_id, status, published_at, max_tickets_per_user, queue_required, version)
    VALUES (v_event_swift_id, 'Taylor Swift — Eras Tour Finale',
            NOW() + INTERVAL '45 days', v_venue_msg_id, v_organizer_id,
            'ACTIVE', NOW(), 2, true, 0);

    INSERT INTO seats (id, event_id, section, row_label, seat_number, status, price)
    SELECT gen_random_uuid(), v_event_swift_id, sec.section, rows.row_label, seat_num::TEXT, 'AVAILABLE', sec.price
    FROM (VALUES ('Floor', 450.00), ('Lower Bowl', 250.00), ('Upper Bowl', 120.00), ('VIP Suite', 1200.00)) AS sec(section, price),
         (VALUES ('A'), ('B'), ('C'), ('D')) AS rows(row_label),
         generate_series(1, 6) AS seat_num;
  END IF;

  -- =========================================================
  -- Event 2: Beyonce Renaissance Tour (mostly sold out)
  --   Demonstrates: scarcity visuals on seat map, only a handful of green seats
  -- =========================================================
  IF NOT EXISTS (SELECT 1 FROM events WHERE title = 'Beyoncé Renaissance Tour') THEN
    v_event_bey_id := gen_random_uuid();
    INSERT INTO events (id, title, start_time, venue_id, organizer_id, status, published_at, max_tickets_per_user, queue_required, version)
    VALUES (v_event_bey_id, 'Beyoncé Renaissance Tour',
            NOW() + INTERVAL '30 days', v_venue_sof_id, v_organizer_id,
            'ACTIVE', NOW(), 4, false, 0);

    INSERT INTO seats (id, event_id, section, row_label, seat_number, status, price)
    SELECT gen_random_uuid(), v_event_bey_id, sec.section, rows.row_label, seat_num::TEXT,
           -- Pre-mark 80% of seats as BOOKED so the map shows scarcity
           CASE WHEN random() < 0.20 THEN 'AVAILABLE' ELSE 'BOOKED' END,
           sec.price
    FROM (VALUES ('Field', 350.00), ('Lower Level', 180.00), ('Upper Level', 90.00)) AS sec(section, price),
         (VALUES ('A'), ('B'), ('C'), ('D'), ('E')) AS rows(row_label),
         generate_series(1, 8) AS seat_num;
  END IF;

  -- =========================================================
  -- Event 3: Coachella Day 1 (1 ticket per user)
  --   Demonstrates: strictest anti-scalping cap (max_tickets_per_user=1)
  -- =========================================================
  IF NOT EXISTS (SELECT 1 FROM events WHERE title = 'Coachella Day 1 — Headliner Stage') THEN
    v_event_coach_id := gen_random_uuid();
    INSERT INTO events (id, title, start_time, venue_id, organizer_id, status, published_at, max_tickets_per_user, queue_required, version)
    VALUES (v_event_coach_id, 'Coachella Day 1 — Headliner Stage',
            NOW() + INTERVAL '60 days', v_venue_emp_id, v_organizer_id,
            'ACTIVE', NOW(), 1, true, 0);

    INSERT INTO seats (id, event_id, section, row_label, seat_number, status, price)
    SELECT gen_random_uuid(), v_event_coach_id, sec.section, rows.row_label, seat_num::TEXT, 'AVAILABLE', sec.price
    FROM (VALUES ('GA Pit', 549.00), ('GA Field', 349.00), ('VIP', 1099.00)) AS sec(section, price),
         (VALUES ('A'), ('B'), ('C')) AS rows(row_label),
         generate_series(1, 5) AS seat_num;
  END IF;

  -- =========================================================
  -- Event 4: Comedy Cellar - Late Show (small intimate venue)
  --   Demonstrates: small seat map, NYC for /near-me, low-price tier
  -- =========================================================
  IF NOT EXISTS (SELECT 1 FROM events WHERE title = 'Comedy Cellar — Late Show') THEN
    v_event_com_id := gen_random_uuid();
    INSERT INTO events (id, title, start_time, venue_id, organizer_id, status, published_at, max_tickets_per_user, queue_required, version)
    VALUES (v_event_com_id, 'Comedy Cellar — Late Show',
            NOW() + INTERVAL '3 days', v_venue_cmc_id, v_organizer_id,
            'ACTIVE', NOW(), 4, false, 0);

    INSERT INTO seats (id, event_id, section, row_label, seat_number, status, price)
    SELECT gen_random_uuid(), v_event_com_id, sec.section, rows.row_label, seat_num::TEXT, 'AVAILABLE', sec.price
    FROM (VALUES ('Front Tables', 45.00), ('Bar Seats', 25.00)) AS sec(section, price),
         (VALUES ('A'), ('B'), ('C')) AS rows(row_label),
         generate_series(1, 4) AS seat_num;
  END IF;

END $$;
