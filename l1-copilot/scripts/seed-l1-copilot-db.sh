#!/usr/bin/env bash
# =============================================================================
# seed-l1-copilot-db.sh
# Truncates and reloads the demo seed of the `l1_copilot` database.
#
# backend/src/main/resources/data-postgresql.sql already truncates tickets and
# orders, deletes users and inserts again, so it is idempotent and can be run
# as often as needed.
#
# Requires:
#   - The shared Postgres reachable from the host (postgres.local:15432 by
#     default, NodePort of the Postgres in the `infra` namespace).
#   - psql on PATH (PostgreSQL for Windows or the WSL package).
#
# Credentials come from infra/.env.local when present: POSTGRES_USER and
# POSTGRES_PASSWORD (the Spring/Kubernetes names) are mapped to PGUSER and
# PGPASSWORD, which is what psql/libpq understands.
#
# Environment variables (with defaults):
#   PGHOST      (default: postgres.local)
#   PGPORT      (default: 15432)
#   PGUSER      (default: POSTGRES_USER from infra/.env.local)
#   PGPASSWORD  (default: POSTGRES_PASSWORD from infra/.env.local, required)
#   PGDATABASE  (default: l1_copilot)
# =============================================================================

set -euo pipefail

ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/infra/.env.local"
if [ -f "$ENV_FILE" ]; then
  set -a; source "$ENV_FILE"; set +a
fi

# Map POSTGRES_* (Spring/Kubernetes) to PG* (psql) unless PG* are already set
: "${PGUSER:=${POSTGRES_USER:?Define POSTGRES_USER in infra/.env.local}}"
: "${PGPASSWORD:=${POSTGRES_PASSWORD:?Define POSTGRES_PASSWORD in infra/.env.local}}"
: "${PGHOST:=postgres.local}"
: "${PGPORT:=15432}"
: "${PGDATABASE:=l1_copilot}"
# Native Windows psql takes client_encoding from the ANSI codepage unless it is set,
# and reads the UTF-8 SQL as Latin1 ("García" -> "GarcÃ­a"). Forcing UTF8 avoids it
# whatever the codepage of the calling console.
PGCLIENTENCODING=UTF8
export PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD PGCLIENTENCODING

SEED_FILE="backend/src/main/resources/data-postgresql.sql"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

command -v psql >/dev/null 2>&1 || fail "psql not found on PATH."
[ -f "$SEED_FILE" ] || fail "$SEED_FILE not found (are you at the repo root?)."

step "Reloading the seed into $PGDATABASE@$PGHOST:$PGPORT"
psql -v ON_ERROR_STOP=1 -f "$SEED_FILE"

ROWS=$(psql -tAc "SELECT COUNT(*) FROM orders;")
ok "Seed loaded: $ROWS orders in table orders"
