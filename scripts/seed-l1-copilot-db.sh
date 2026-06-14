#!/usr/bin/env bash
# =============================================================================
# seed-l1-copilot-db.sh
# Limpia (TRUNCATE) y recarga el seed de demo de la BD `l1_copilot`.
#
# El SQL en backend/src/main/resources/data-postgresql.sql ya hace
# TRUNCATE tickets, TRUNCATE orders, DELETE users y luego INSERT — es
# idempotente: se puede ejecutar tantas veces como haga falta.
#
# Requisitos:
#   - Postgres compartido accesible desde el host (postgres.local:15432 por
#     defecto, NodePort del Postgres del namespace `infra`).
#   - psql en el PATH (instalación de PostgreSQL en Windows o paquete en WSL).
#
# Variables de entorno (opcionales):
#   PGHOST      (default: postgres.local)
#   PGPORT      (default: 15432)
#   PGUSER      (default: appuser)
#   PGDATABASE  (default: l1_copilot)
#   PGPASSWORD  (default: change-me  — el del script 03-setup-postgres.sh)
# =============================================================================

set -euo pipefail

: "${PGHOST:=postgres.local}"
: "${PGPORT:=15432}"
: "${PGUSER:=appuser}"
: "${PGDATABASE:=l1_copilot}"
: "${PGPASSWORD:=change-me}"
export PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD

SEED_FILE="backend/src/main/resources/data-postgresql.sql"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

command -v psql >/dev/null 2>&1 || fail "psql no encontrado en el PATH."
[ -f "$SEED_FILE" ] || fail "No se encuentra $SEED_FILE (¿estás en la raíz del repo?)."

step "Recargando seed en $PGDATABASE@$PGHOST:$PGPORT"
psql -v ON_ERROR_STOP=1 -f "$SEED_FILE"

ROWS=$(psql -tAc "SELECT COUNT(*) FROM orders;")
ok "Seed cargado — $ROWS pedidos en la tabla orders"
