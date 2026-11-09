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
# Credenciales: el script carga `infra/.env.local` si existe
# si existen (mismo patrón que las Run Configurations de IntelliJ con el plugin
# EnvFile). El .env trae POSTGRES_USER/POSTGRES_PASSWORD (nombres Spring/K8s);
# aquí se mapean a PGUSER/PGPASSWORD que es lo que entiende psql/libpq.
#
# Variables de entorno reconocidas (con fallbacks):
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

# Mapear nombres POSTGRES_* (Spring/K8s) → PG* (psql) si no se definieron PG* directamente
: "${PGUSER:=${POSTGRES_USER:?Define POSTGRES_USER in infra/.env.local}}"
: "${PGPASSWORD:=${POSTGRES_PASSWORD:?Define POSTGRES_PASSWORD in infra/.env.local}}"
: "${PGHOST:=postgres.local}"
: "${PGPORT:=15432}"
: "${PGDATABASE:=l1_copilot}"
# psql nativo de Windows toma el client_encoding del codepage ANSI del sistema
# si no se fija explícitamente, y reinterpreta el UTF-8 del .sql como Latin1
# al insertar (mojibake: "García" -> "GarcÃ­a"). Forzar UTF8 evita el problema
# sea cual sea el codepage de la consola desde la que se invoque el script.
PGCLIENTENCODING=UTF8
export PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD PGCLIENTENCODING

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
