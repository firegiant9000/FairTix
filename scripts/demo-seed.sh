#!/usr/bin/env bash
# FairTix demo seed — creates admin + userA + userB, marks them email-verified,
# promotes admin role, and prints the Bayou Pelicans vs Harbor Foxes event/seat IDs.
#
# Prereqs: docker compose stack already running and healthy.
#   docker compose up --build -d
#   (wait until backend is reachable on http://localhost:8080)
#
# Usage:
#   ./scripts/demo-seed.sh           # seed users + print IDs
#   ./scripts/demo-seed.sh reset     # release seats + delete demo holds/tickets/orders
#   ./scripts/demo-seed.sh ids       # just print IDs
#
# Env: API (default http://localhost:8080), PG_CONTAINER (default fairtix-postgres).
# A non-localhost API needs DEMO_SEED_ALLOW_REMOTE=1 and an explicit PG_CONTAINER; only for
# a private, throwaway stack whose database runs in a container on this machine.

set -euo pipefail

API="${API:-http://localhost:8080}"
API="${API%/}"

# The demo accounts have a fixed, published password (one of them is ADMIN), so never
# create them on anything reachable from outside this machine.
# Anchored, so http://localhost:8080@evil.com and http://localhost.evil.com don't match.
if [[ ! "${API}" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]]; then
  if [[ "${DEMO_SEED_ALLOW_REMOTE:-}" != "1" ]]; then
    echo "Refusing to seed ${API}: demo accounts use a fixed password." >&2
    echo "Set DEMO_SEED_ALLOW_REMOTE=1 only for a private, throwaway environment." >&2
    exit 1
  fi
  # Every SQL step runs through `docker exec` on this machine, so it can only reach a
  # Postgres container here. Defaulting to this machine's own stack would register the
  # users at ${API} but verify, promote and seed them in a different database, and still
  # report success. A hosted database (Railway, Neon) has no container here: not supported.
  if [[ -z "${PG_CONTAINER:-}" ]]; then
    echo "Refusing to seed ${API} without PG_CONTAINER: name the container on this machine that holds that API's database." >&2
    exit 1
  fi
fi
PG_CONTAINER="${PG_CONTAINER:-fairtix-postgres}"

# Load DB creds from .env
set -a; source .env; set +a
# ON_ERROR_STOP makes psql exit non-zero on the first failed statement, so set -e stops here.
PSQL="docker exec -i ${PG_CONTAINER} psql -U ${POSTGRES_USER} -d ${POSTGRES_DB} -At -v ON_ERROR_STOP=1"

ADMIN_EMAIL="admin@fairtix.demo"
USER_A_EMAIL="userA@fairtix.demo"
USER_B_EMAIL="userB@fairtix.demo"
PASSWORD="Demo#2026!"

# Every WARN bumps this; a seed run that warned still prints the IDs, then exits 1.
WARNINGS=0
warn() {
  echo "  WARN: $*" >&2
  WARNINGS=$((WARNINGS + 1))
}

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
    warn "register ${email} returned ${code}"
  fi
}

print_ids() {
  echo
  echo "=== Demo IDs (paste into the script / curl backups) ==="
  ${PSQL} <<'SQL'
\echo '-- Bayou Pelicans vs Harbor Foxes event:'
SELECT 'EVENT_ID=' || id FROM events WHERE title = 'Bayou Pelicans vs Harbor Foxes';

\echo ''
\echo '-- Two AVAILABLE Lower Bowl A seats (use these for the hold):'
SELECT 'SEAT_' || seat_number || '_ID=' || s.id
FROM seats s
JOIN events e ON e.id = s.event_id
WHERE e.title = 'Bayou Pelicans vs Harbor Foxes'
  AND s.section = 'Lower Bowl'
  AND s.row_label = 'A'
  AND s.status = 'AVAILABLE'
ORDER BY s.seat_number
LIMIT 2;

\echo ''
\echo '-- Demo users:'
SELECT email, role, email_verified FROM users
WHERE lower(email) IN ('admin@fairtix.demo','usera@fairtix.demo','userb@fairtix.demo')
ORDER BY email;
SQL
  echo "======================================================="
}

reset_demo_state() {
  echo "Resetting demo seat/hold/ticket state for Bayou Pelicans vs Harbor Foxes…"
  ${PSQL} <<'SQL'
DO $$
DECLARE v_event UUID;
BEGIN
  SELECT id INTO v_event FROM events WHERE title = 'Bayou Pelicans vs Harbor Foxes';
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
# lower() on both sides: registration stores addresses lower-cased (userA@ becomes usera@).
${PSQL} <<SQL
UPDATE users SET email_verified = true
 WHERE lower(email) IN (lower('${ADMIN_EMAIL}'),lower('${USER_A_EMAIL}'),lower('${USER_B_EMAIL}'));

UPDATE users SET role = 'ADMIN' WHERE lower(email) = lower('${ADMIN_EMAIL}');
SQL

# V29 seeds nothing on a database that had no users when it ran, so on a fresh stack it is
# re-run here and V55 then renames what it inserted. A group is re-run only when none of its
# events exist (counted by seeded and V55 titles) and none of its venues has been renamed:
# V27 inserts without checking, V29 checks only the seeded titles, and both look venues up
# by their real names, so any other re-run would duplicate events or bring back real venue
# names that V55 can no longer rename. Those cases need a fresh database.
REBUILD_HINT="rebuild the demo database (docker compose down -v, then up; this deletes ALL local database data) and run this again"
echo "3/3  Ensuring demo events exist (re-run V27/V29 inline if missing, then V55) …"
V27_COUNT=$(${PSQL} -c "SELECT COUNT(*) FROM events WHERE title IN ('Jazz Fest 2026','Houston Rodeo Night','Saints vs Panthers','Crescent Jazz Weekend','Gulf Coast Rodeo Night','Bayou Pelicans vs Harbor Foxes');")
V27_RENAMED=$(${PSQL} -c "SELECT COUNT(*) FROM venues WHERE name IN ('Crescent Demo Arena','Bayou Demo Center','Riverfront Demo Dome');")
if [[ "${V27_COUNT}" -eq 0 && "${V27_RENAMED}" -eq 0 ]]; then
  echo "  V27 events missing. Re-running V27 inline…"
  ${PSQL} < backend/src/main/resources/db/migration/V27__seed_demo_events.sql
elif [[ "${V27_COUNT}" -lt 3 ]]; then
  warn "${V27_COUNT} of 3 V27 demo events exist and re-seeding would duplicate events or restore real names; ${REBUILD_HINT}."
fi
V29_COUNT=$(${PSQL} -c "SELECT COUNT(*) FROM events WHERE title IN ('Taylor Swift — Eras Tour Finale','Beyoncé Renaissance Tour','Coachella Day 1 — Headliner Stage','Comedy Cellar — Late Show','Nova Lane - Arena Tour Finale','The Velvet Static - Live','Sunfield Festival - Day 1','Basement Comedy - Late Show');")
V29_RENAMED=$(${PSQL} -c "SELECT COUNT(*) FROM venues WHERE name IN ('Midtown Demo Garden','Harbor Demo Stadium','Sunfield Demo Grounds','The Demo Basement');")
if [[ "${V29_COUNT}" -eq 0 && "${V29_RENAMED}" -eq 0 ]]; then
  echo "  V29 events missing. Re-running V29 inline…"
  ${PSQL} < backend/src/main/resources/db/migration/V29__seed_extra_demo_events.sql
elif [[ "${V29_COUNT}" -lt 4 ]]; then
  warn "${V29_COUNT} of 4 V29 demo events exist and re-seeding would duplicate events or restore real names; ${REBUILD_HINT}."
fi
# Every run, not only after a re-seed: V55 is idempotent, and this retries a rename that
# failed on an earlier run after its re-seed had already committed.
echo "  Renaming seed events and venues (V55 inline)…"
${PSQL} < backend/src/main/resources/db/migration/V55__fictional_demo_seed_names.sql
# Matched by name and address like V55, so an organizer's own venue that shares a name with
# a seed venue doesn't count.
REAL_VENUES=$(${PSQL} -c "SELECT COUNT(*) FROM venues v JOIN (VALUES ('Smoothie King Center','1501 Dave Dixon Dr'),('Toyota Center','1510 Polk St'),('Caesars Superdome','1500 Sugar Bowl Dr'),('Madison Square Garden','4 Pennsylvania Plaza'),('SoFi Stadium','1001 S Stadium Dr'),('Empire Polo Club','81-800 Avenue 51'),('Comedy Cellar','117 MacDougal St')) AS seed(name, address) ON v.name = seed.name AND v.address = seed.address;")
if [[ "${REAL_VENUES}" -gt 0 ]]; then
  warn "${REAL_VENUES} seed venue(s) still have their real name and address. V55 leaves a seed venue alone while a non-seed event uses it, so check which events use them; otherwise ${REBUILD_HINT}."
fi

print_ids

cat <<EOF

Done. Login with any of these in the UI (no MailHog click needed):
  ${ADMIN_EMAIL}    / ${PASSWORD}    (role=ADMIN)
  ${USER_A_EMAIL}   / ${PASSWORD}    (User A — main demo)
  ${USER_B_EMAIL}   / ${PASSWORD}    (User B — incognito for conflict)

Between rehearsals, run:  ./scripts/demo-seed.sh reset
EOF

if [[ "${WARNINGS}" -gt 0 ]]; then
  echo >&2
  echo "Finished with ${WARNINGS} warning(s); see WARN lines above." >&2
  exit 1
fi
