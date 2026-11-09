#!/usr/bin/env bash
# =============================================================================
# setup-l1-copilot-db.sh
# Creates the `l1_copilot` database in the shared PostgreSQL and applies the
# project schema, then calls seed-l1-copilot-db.sh to load the seed.
#
# One database per project inside the cluster's single Postgres engine
# (see infra/INFRA.md and l1-copilot/docs/infraestructura/03-infra-compartida.md).
#
# Idempotent:
#   - The database is only created when missing.
#   - The schema uses CREATE TABLE IF NOT EXISTS.
#   - The seed (data-postgresql.sql) truncates and inserts, so it can be reapplied.
#
# Requires:
#   - The shared Postgres reachable from the host (postgres.local:15432 by
#     default, NodePort of the Postgres in the `infra` namespace).
#   - psql on PATH.
#
# Credentials come from infra/.env.local when present: POSTGRES_USER and
# POSTGRES_PASSWORD are mapped to PGUSER and PGPASSWORD.
#
# Environment variables (with defaults):
#   PGHOST      (default: postgres.local)
#   PGPORT      (default: 15432)
#   PGUSER      (default: POSTGRES_USER from infra/.env.local)
#   PGPASSWORD  (default: POSTGRES_PASSWORD from infra/.env.local, required)
#   PROJECT_DB  (default: l1_copilot)
#
# Flags:
#   --no-seed   Only creates the database and applies the schema; loads no data.
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
: "${PROJECT_DB:=l1_copilot}"
# See seed-l1-copilot-db.sh: forcing UTF8 stops native Windows psql from reading
# the SQL with the system ANSI codepage.
PGCLIENTENCODING=UTF8
export PGHOST PGPORT PGUSER PGPASSWORD PGCLIENTENCODING

SCHEMA_FILE="backend/src/main/resources/schema-postgresql.sql"
SEED_SCRIPT="$(dirname "$0")/seed-l1-copilot-db.sh"

NO_SEED=false
for arg in "$@"; do
  case "$arg" in
    --no-seed) NO_SEED=true ;;
    -h|--help)
      echo "Usage: $0 [--no-seed]"
      echo "  Creates database $PROJECT_DB, applies the schema and loads the seed."
      echo "  --no-seed   Skips seed-l1-copilot-db.sh at the end."
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# -----------------------------------------------------------------------------
# 0. Requirements
# -----------------------------------------------------------------------------
step "Checking requirements..."
command -v psql >/dev/null 2>&1 || fail "psql not found on PATH."
[ -f "$SCHEMA_FILE" ] || fail "$SCHEMA_FILE not found (are you at the repo root?)."

if ! psql -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
  fail "Cannot connect to $PGHOST:$PGPORT as $PGUSER. Is Rancher Desktop running and NodePort 15432 exposed?"
fi
ok "Postgres connection OK ($PGHOST:$PGPORT as $PGUSER)"

# -----------------------------------------------------------------------------
# 1. Create the project database (idempotent)
# -----------------------------------------------------------------------------
step "Creating database '$PROJECT_DB' (if missing)..."

DB_EXISTS=$(psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$PROJECT_DB';")

if [ "$DB_EXISTS" = "1" ]; then
  warn "Database '$PROJECT_DB' already exists."
else
  psql -d postgres -c "CREATE DATABASE $PROJECT_DB OWNER $PGUSER;"
  ok "Database '$PROJECT_DB' created"
fi

# -----------------------------------------------------------------------------
# 2. Apply the schema (idempotent with CREATE TABLE IF NOT EXISTS)
# -----------------------------------------------------------------------------
step "Applying $SCHEMA_FILE..."
psql -d "$PROJECT_DB" -v ON_ERROR_STOP=1 -f "$SCHEMA_FILE"
ok "Schema applied"

# -----------------------------------------------------------------------------
# 3. Seed
# -----------------------------------------------------------------------------
if $NO_SEED; then
  warn "Seed skipped (--no-seed). To load it later: bash $SEED_SCRIPT"
else
  step "Loading the seed with $SEED_SCRIPT"
  PGDATABASE="$PROJECT_DB" bash "$SEED_SCRIPT"
fi

cat <<EOF

============================================================
  L1 Copilot database ready
============================================================
  External host (host)      : $PGHOST:$PGPORT
  Internal host (in-cluster): postgres.infra.svc.cluster.local:5432
  Database                  : $PROJECT_DB
  User                      : $PGUSER
------------------------------------------------------------
  JDBC URL for the backend (prod profile):
  jdbc:postgresql://postgres.infra.svc.cluster.local:5432/$PROJECT_DB
============================================================
EOF
