#!/usr/bin/env bash
# Wipe and re-seed the staging database. Refuses to run unless STAGING_DB_URL is set
# AND points at a host containing "staging" (last-ditch guard against pointing this
# at prod by accident).
#
# Usage:
#   STAGING_DB_URL='postgresql://user:pw@host:port/fairtix' ./scripts/reset-staging.sh

set -euo pipefail

if [[ -z "${STAGING_DB_URL:-}" ]]; then
  echo "ERROR: STAGING_DB_URL is not set." >&2
  exit 2
fi

if [[ "${STAGING_DB_URL}" != *staging* ]]; then
  echo "ERROR: STAGING_DB_URL does not contain 'staging'." >&2
  echo "Refusing to run — name the staging DB with 'staging' in the host or db name." >&2
  exit 2
fi

read -r -p "About to drop and recreate the public schema on a STAGING database. Type 'reset' to continue: " confirm
if [[ "${confirm}" != "reset" ]]; then
  echo "Aborted."
  exit 1
fi

echo "Dropping public schema…"
psql "${STAGING_DB_URL}" <<'SQL'
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO PUBLIC;
SQL

echo "Schema dropped. Restart the backend service — Flyway will re-apply V1..V29"
echo "and any post-V29 migrations on the next boot."
echo
echo "After Flyway finishes, run scripts/demo-seed.sh against the staging API to"
echo "load demo users + events."
