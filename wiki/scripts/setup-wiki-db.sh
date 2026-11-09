#!/usr/bin/env bash
# =============================================================================
# setup-wiki-db.sh
# Crea la BD `wiki` (vacía) dentro del PostgreSQL compartido. A diferencia de
# l1-copilot/scripts/setup-l1-copilot-db.sh, NO aplica ningún schema: Wiki.js
# migra su propio esquema al arrancar contra una BD vacía (ver
# infra/scripts/08-setup-wikijs.sh, que despliega el pod después de este script).
#
# Idempotente: la BD solo se crea si no existe.
#
# Requisitos:
#   - Postgres compartido accesible desde el host (postgres.local:15432 por
#     defecto, NodePort del Postgres del namespace `infra`).
#   - psql en el PATH.
#
# Variables de entorno reconocidas (con fallbacks):
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
# 0. Requisitos
# -----------------------------------------------------------------------------
step "Verificando requisitos..."
command -v psql >/dev/null 2>&1 || fail "psql no encontrado en el PATH."

if ! psql -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
  fail "No se puede conectar a $PGHOST:$PGPORT como $PGUSER. ¿Está Rancher Desktop arrancado y el NodePort 15432 expuesto?"
fi
ok "Conexión a Postgres OK ($PGHOST:$PGPORT como $PGUSER)"

# -----------------------------------------------------------------------------
# 1. Crear la BD (idempotente, sin schema: Wiki.js migra la suya al arrancar)
# -----------------------------------------------------------------------------
step "Creando BD '$PROJECT_DB' (si no existe)..."

DB_EXISTS=$(psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$PROJECT_DB';")

if [ "$DB_EXISTS" = "1" ]; then
  warn "BD '$PROJECT_DB' ya existe."
else
  psql -d postgres -c "CREATE DATABASE $PROJECT_DB OWNER $PGUSER;"
  ok "BD '$PROJECT_DB' creada"
fi

cat <<EOF

============================================================
  BD de Wiki.js lista
============================================================
  Host externo (host)      : $PGHOST:$PGPORT
  Host interno (en-cluster): postgres.infra.svc.cluster.local:5432
  Base de datos            : $PROJECT_DB
  Usuario                  : $PGUSER
------------------------------------------------------------
  Siguiente paso: infra/scripts/08-setup-wikijs.sh
  (despliega el pod, que migra el esquema de Wiki.js al arrancar)
============================================================
EOF
