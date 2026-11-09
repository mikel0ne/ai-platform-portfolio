#!/usr/bin/env bash
# =============================================================================
# setup-wiki-db.sh
# Creates the empty `wiki` database in the shared PostgreSQL. Unlike
# l1-copilot/scripts/setup-l1-copilot-db.sh it applies no schema: Wiki.js
# migrates its own schema on startup against an empty database (see
# infra/scripts/08-setup-wikijs.sh, which deploys the pod after this script).
#
# Idempotent: the database is only created when missing.
#
# Requires:
#   - The shared Postgres reachable from the host (postgres.local:15432 by
#     default, NodePort of the Postgres in the `infra` namespace).
#   - psql on PATH.
#
# Environment variables (with defaults):
#   PGHOST      (default: postgres.local)
#   PGPORT      (default: 15432)
#   PGUSER      (default: POSTGRES_USER from infra/.env.local)
#   PGPASSWORD  (default: POSTGRES_PASSWORD from infra/.env.local, required)
#   PROJECT_DB  (default: wiki)
# =============================================================================

set -euo pipefail

ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/infra/.env.local"
if [ -f "$ENV_FILE" ]; then
  set -a; source "$ENV_FILE"; set +a
fi
: "${PGUSER:=${POSTGRES_USER:?Define POSTGRES_USER in infra/.env.local}}"
: "${PGPASSWORD:=${POSTGRES_PASSWORD:?Define POSTGRES_PASSWORD in infra/.env.local}}"
: "${PGHOST:=postgres.local}"
: "${PGPORT:=15432}"
: "${PROJECT_DB:=wiki}"
PGCLIENTENCODING=UTF8
export PGHOST PGPORT PGUSER PGPASSWORD PGCLIENTENCODING

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# -----------------------------------------------------------------------------
# 0. Requirements
# -----------------------------------------------------------------------------
step "Checking requirements..."
command -v psql >/dev/null 2>&1 || fail "psql not found on PATH."

if ! psql -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
  fail "Cannot connect to $PGHOST:$PGPORT as $PGUSER. Is Rancher Desktop running and NodePort 15432 exposed?"
fi
ok "Postgres connection OK ($PGHOST:$PGPORT as $PGUSER)"

# -----------------------------------------------------------------------------
# 1. Create the database (idempotent, no schema: Wiki.js migrates its own on startup)
# -----------------------------------------------------------------------------
step "Creating database '$PROJECT_DB' (if missing)..."

DB_EXISTS=$(psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$PROJECT_DB';")

if [ "$DB_EXISTS" = "1" ]; then
  warn "Database '$PROJECT_DB' already exists."
else
  psql -d postgres -c "CREATE DATABASE $PROJECT_DB OWNER $PGUSER;"
  ok "Database '$PROJECT_DB' created"
fi

cat <<EOF

============================================================
  Wiki.js database ready
============================================================
  External host (host)      : $PGHOST:$PGPORT
  Internal host (in-cluster): postgres.infra.svc.cluster.local:5432
  Database                  : $PROJECT_DB
  User                      : $PGUSER
------------------------------------------------------------
  Next step: infra/scripts/08-setup-wikijs.sh
  (deploys the pod, which migrates the Wiki.js schema on startup)
============================================================
EOF
