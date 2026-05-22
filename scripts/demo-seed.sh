#!/usr/bin/env bash
# FairTix demo seed — creates admin + userA + userB, marks them email-verified,
# promotes admin role, and prints the Saints vs Panthers event/seat IDs.
#
# Prereqs: docker compose stack already running and healthy.
#   docker compose up --build -d
#   (wait until backend is reachable on http://localhost:8080)
#
# Usage:
#   ./scripts/demo-seed.sh           # seed users + print IDs
#   ./scripts/demo-seed.sh reset     # release seats + delete demo holds/tickets/orders
#   ./scripts/demo-seed.sh ids       # just print IDs

set -euo pipefail

API="${API:-http://localhost:8080}"
PG_CONTAINER="${PG_CONTAINER:-fairtix-postgres}"

# Load DB creds from .env
set -a; source .env; set +a
PSQL="docker exec -i ${PG_CONTAINER} psql -U ${POSTGRES_USER} -d ${POSTGRES_DB} -At"

ADMIN_EMAIL="admin@fairtix.demo"
USER_A_EMAIL="userA@fairtix.demo"
USER_B_EMAIL="userB@fairtix.demo"
PASSWORD="Demo#2026!"

register() {
  local email="$1"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${API}/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${PASSWORD}\"}")
  if [[ "$code" == "200" || "$code" == "201" || "$code" == "204" ]]; then
    echo "  registered ${email}"
  elif [[ "$code" == "409" || "$code" == "400" ]]; then
    echo "  ${email} already exists (${code}) — skipping"
  else
    echo "  WARN: register ${email} returned ${code}"
  fi
}

print_ids() {
  echo
  echo "=== Demo IDs (paste into the script / curl backups) ==="
  ${PSQL} <<'SQL'
\echo '-- Saints vs Panthers event:'
SELECT 'EVENT_ID=' || id FROM events WHERE title = 'Saints vs Panthers';

\echo ''
\echo '-- Two AVAILABLE Lower Bowl A seats (use these for the hold):'
SELECT 'SEAT_' || seat_number || '_ID=' || s.id
FROM seats s
JOIN events e ON e.id = s.event_id
WHERE e.title = 'Saints vs Panthers'
  AND s.section = 'Lower Bowl'
  AND s.row_label = 'A'
  AND s.status = 'AVAILABLE'
ORDER BY s.seat_number
LIMIT 2;

\echo ''
\echo '-- Demo users:'
SELECT email, role, email_verified FROM users
WHERE email IN ('admin@fairtix.demo','userA@fairtix.demo','userB@fairtix.demo')
ORDER BY email;
SQL
  echo "======================================================="
}

reset_demo_state() {
  echo "Resetting demo seat/hold/ticket state for Saints vs Panthers…"
  ${PSQL} <<'SQL'
DO $$
DECLARE v_event UUID;
BEGIN
  SELECT id INTO v_event FROM events WHERE title = 'Saints vs Panthers';
  IF v_event IS NULL THEN RAISE NOTICE 'no event found'; RETURN; END IF;

  DELETE FROM seat_holds WHERE seat_id IN (SELECT id FROM seats WHERE event_id = v_event);
  DELETE FROM tickets    WHERE event_id = v_event;
  UPDATE seats SET status = 'AVAILABLE' WHERE event_id = v_event;
  -- (orphan orders left in place — order_holds FK makes cleanup tricky and they
  -- don't affect the demo since /my-tickets reads from tickets, not orders)
END $$;
SQL
  echo "Reset complete."
}

case "${1:-seed}" in
  ids)   print_ids; exit 0 ;;
  reset) reset_demo_state; print_ids; exit 0 ;;
  seed)  ;;
  *) echo "usage: $0 [seed|reset|ids]"; exit 1 ;;
esac

echo "1/3  Registering demo users via ${API}/auth/register …"
register "${ADMIN_EMAIL}"
register "${USER_A_EMAIL}"
register "${USER_B_EMAIL}"

echo "2/3  Marking emails verified + promoting admin role …"
${PSQL} <<SQL
UPDATE users SET email_verified = true
 WHERE email IN ('${ADMIN_EMAIL}','${USER_A_EMAIL}','${USER_B_EMAIL}');

UPDATE users SET role = 'ADMIN' WHERE email = '${ADMIN_EMAIL}';
SQL

echo "3/3  Ensuring demo events exist (re-run V27/V29 inline if missing) …"
V27_COUNT=$(${PSQL} -c "SELECT COUNT(*) FROM events WHERE title IN ('Jazz Fest 2026','Houston Rodeo Night','Saints vs Panthers');")
if [[ "${V27_COUNT}" -lt 3 ]]; then
  echo "  V27 events missing (count=${V27_COUNT}). Re-running V27 inline…"
  ${PSQL} < backend/src/main/resources/db/migration/V27__seed_demo_events.sql
fi
V29_COUNT=$(${PSQL} -c "SELECT COUNT(*) FROM events WHERE title IN ('Taylor Swift — Eras Tour Finale','Beyoncé Renaissance Tour','Coachella Day 1 — Headliner Stage','Comedy Cellar — Late Show');")
if [[ "${V29_COUNT}" -lt 4 ]]; then
  echo "  V29 events missing (count=${V29_COUNT}). Re-running V29 inline…"
  ${PSQL} < backend/src/main/resources/db/migration/V29__seed_extra_demo_events.sql
fi

print_ids

cat <<EOF

Done. Login with any of these in the UI (no MailHog click needed):
  ${ADMIN_EMAIL}    / ${PASSWORD}    (role=ADMIN)
  ${USER_A_EMAIL}   / ${PASSWORD}    (User A — main demo)
  ${USER_B_EMAIL}   / ${PASSWORD}    (User B — incognito for conflict)

Between rehearsals, run:  ./scripts/demo-seed.sh reset
EOF
