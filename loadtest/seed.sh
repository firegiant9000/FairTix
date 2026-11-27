#!/usr/bin/env bash
# Seeds a load-test run: registers synthetic users through the API, marks them
# email-verified, and creates a fresh queued event with its own seats. Prints the
# EVENT_ID to pass to k6. Run from the repo root against the fairtix-loadtest stack
# (see loadtest/README.md).
#
# Usage: ./loadtest/seed.sh [USERS] [SEATS] [CAP_PER_USER]
#   defaults: 500 users, 200 seats, 2 tickets per user
#
# Env: API (default http://localhost:8080), PG_CONTAINER (default fairtix-lt-postgres),
#      PARALLEL (concurrent registrations, default 8).
#
# Every user shares one fixed, published password, so this only runs against
# localhost.

set -euo pipefail

USERS="${1:-500}"
SEATS="${2:-200}"
CAP="${3:-2}"
API="${API:-http://localhost:8080}"
API="${API%/}"
PG_CONTAINER="${PG_CONTAINER:-fairtix-lt-postgres}"
PARALLEL="${PARALLEL:-8}"

if [[ ! "${API}" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]]; then
  echo "Refusing to seed ${API}: load users share a published password. Localhost only." >&2
  exit 1
fi
for n in "${USERS}" "${SEATS}" "${CAP}"; do
  if [[ ! "${n}" =~ ^[1-9][0-9]*$ ]]; then
    echo "USERS, SEATS and CAP_PER_USER must be positive integers" >&2
    exit 1
  fi
done

# The overlay keeps the base file's host ports, so make sure localhost:8080 is the
# load-test stack and not a dev stack before registering anything.
for c in fairtix-lt-backend "${PG_CONTAINER}"; do
  if [[ "$(docker inspect -f '{{.State.Running}}' "${c}" 2>/dev/null)" != "true" ]]; then
    echo "Container ${c} is not running; start the fairtix-loadtest stack first (loadtest/README.md)." >&2
    exit 1
  fi
done

# k6 --summary-export does not create its directory.
mkdir -p loadtest/results
# psql reads the database name and user from the container's own environment,
# so .env is never executed as shell.
psql_lt() {
  docker exec -i "${PG_CONTAINER}" sh -c \
    'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v ON_ERROR_STOP=1 "$@"' psql "$@"
}

# Must match PASSWORD and EMAIL_DOMAIN in queue-checkout.js.
export LT_PASSWORD="LoadTest#2026!"
export LT_DOMAIN="load.fairtix.test"
export API

register_one() {
  local email="$1" code attempt
  # 000 is a dropped connection (seen with Docker Desktop's port forwarding); retry it.
  for attempt in 1 2 3; do
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${API}/auth/register" \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"${email}\",\"password\":\"${LT_PASSWORD}\"}") || true
    [[ "${code}" != "000" ]] && break
    sleep 1
  done
  case "${code}" in
    200|201|409) ;;   # 409: already registered by an earlier run
    *) echo "register ${email} returned ${code}" >&2; return 1 ;;
  esac
}
export -f register_one

echo "1/3  Registering organizer + ${USERS} users (${PARALLEL} at a time)…"
register_one "organizer@${LT_DOMAIN}"
seq 1 "${USERS}" | sed "s/.*/load&@${LT_DOMAIN}/" \
  | xargs -P "${PARALLEL}" -I{} bash -c 'register_one "$1"' _ {}

echo "2/3  Marking load users email-verified…"
psql_lt -c "UPDATE users SET email_verified = true WHERE email LIKE '%@${LT_DOMAIN}';" >/dev/null

echo "3/3  Creating a queued event with ${SEATS} seats, cap ${CAP} per user…"
EVENT_ID=$(psql_lt <<SQL | tail -n 1
DO \$\$
DECLARE
  v_org   UUID;
  v_venue UUID;
  v_event UUID := gen_random_uuid();
BEGIN
  SELECT id INTO v_org FROM users WHERE email = 'organizer@${LT_DOMAIN}';

  INSERT INTO venues (id, name, address, city, country, capacity)
  VALUES (gen_random_uuid(), 'Load Test Arena', '1 Synthetic Way', 'Nowhere', 'US', ${SEATS})
  ON CONFLICT (name) DO UPDATE SET capacity = GREATEST(venues.capacity, EXCLUDED.capacity);
  SELECT id INTO v_venue FROM venues WHERE name = 'Load Test Arena';

  -- No organization_id: the per-organisation daily sales cap would stop a load
  -- run at \$1k. queue_capacity NULL: an uncapped waiting room.
  INSERT INTO events (id, title, start_time, venue_id, organizer_id, status, published_at,
                      max_tickets_per_user, queue_required, version)
  VALUES (v_event, 'Load test ' || to_char(NOW(), 'YYYY-MM-DD HH24:MI:SS'),
          NOW() + INTERVAL '30 days', v_venue, v_org, 'ACTIVE', NOW(), ${CAP}, true, 0);

  INSERT INTO seats (id, event_id, section, row_label, seat_number, status, price)
  SELECT gen_random_uuid(), v_event, 'Floor',
         'R' || ((n - 1) / 20 + 1), ((n - 1) % 20 + 1)::TEXT, 'AVAILABLE', 50.00
  FROM generate_series(1, ${SEATS}) AS n;

  PERFORM set_config('lt.event_id', v_event::TEXT, false);
END \$\$;
SELECT current_setting('lt.event_id');
SQL
)

echo
echo "EVENT_ID=${EVENT_ID}"
echo "Run:  docker compose -p fairtix-loadtest -f docker-compose.yml -f loadtest/docker-compose.loadtest.yml \\"
echo "        run --rm k6 run -e EVENT_ID=${EVENT_ID} -e USERS=${USERS} queue-checkout.js"
