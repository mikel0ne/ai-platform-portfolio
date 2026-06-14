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
# Credenciales: el script carga `infra/.env.l1-copilot` + `infra/.env.l1-copilot.local`
# si existen (mismo patrón que las Run Configurations de IntelliJ con el plugin
# EnvFile). El .env trae POSTGRES_USER/POSTGRES_PASSWORD (nombres Spring/K8s);
# aquí se mapean a PGUSER/PGPASSWORD que es lo que entiende psql/libpq.
#
# Variables de entorno reconocidas (con fallbacks):
#   PGHOST      (default: postgres.local)
#   PGPORT      (default: 15432)
#   PGUSER      (default: POSTGRES_USER del .env, o appuser)
#   PGPASSWORD  (default: POSTGRES_PASSWORD del .env, o change-me)
#   PGDATABASE  (default: l1_copilot)
# =============================================================================

set -euo pipefail

# Cargar .env.l1-copilot (commiteado, defaults) y .env.l1-copilot.local (overrides
# git-ignorados) si existen. set -a exporta automáticamente todo lo definido en ellos.
for env_file in infra/.env.l1-copilot infra/.env.l1-copilot.local; do
  if [ -f "$env_file" ]; then
    set -a; source "$env_file"; set +a
  fi
done

# Mapear nombres POSTGRES_* (Spring/K8s) → PG* (psql) si no se definieron PG* directamente
: "${PGUSER:=${POSTGRES_USER:-appuser}}"
: "${PGPASSWORD:=${POSTGRES_PASSWORD:-change-me}}"
: "${PGHOST:=postgres.local}"
: "${PGPORT:=15432}"
: "${PGDATABASE:=l1_copilot}"
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
