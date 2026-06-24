#!/usr/bin/env bash
# =============================================================================
# setup-l1-copilot-db.sh
# Crea la BD `l1_copilot` dentro del PostgreSQL compartido y aplica el esquema
# del proyecto. Al final llama a seed-l1-copilot-db.sh para cargar el seed.
#
# Modelo: una BD por proyecto dentro del mismo motor Postgres del clúster
# (ver infra/INFRA.md y docs/infraestructura/03-infra-compartida.md).
#
# Idempotente:
#   - La BD solo se crea si no existe (sin error si ya existía).
#   - El schema usa CREATE TABLE IF NOT EXISTS.
#   - El seed (data-postgresql.sql) hace TRUNCATE + INSERT, se puede reaplicar.
#
# Requisitos:
#   - Postgres compartido accesible desde el host (postgres.local:15432 por
#     defecto, NodePort del Postgres del namespace `infra`).
#   - psql en el PATH.
#
# Credenciales: el script carga `infra/.env.l1-copilot` + `infra/.env.l1-copilot.local`
# si existen (mismo patrón que las Run Configurations de IntelliJ con el plugin
# EnvFile). POSTGRES_USER/POSTGRES_PASSWORD del .env se mapean a PGUSER/PGPASSWORD.
#
# Variables de entorno reconocidas (con fallbacks):
#   PGHOST      (default: postgres.local)
#   PGPORT      (default: 15432)
#   PGUSER      (default: POSTGRES_USER del .env, o appuser)
#   PGPASSWORD  (default: POSTGRES_PASSWORD del .env, o change-me)
#   PROJECT_DB  (default: l1_copilot)
#
# Flags:
#   --no-seed   Solo crea la BD y aplica el esquema; no carga datos.
# =============================================================================

set -euo pipefail

# Cargar .env.l1-copilot (commiteado) y .env.l1-copilot.local (overrides) si existen
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
: "${PROJECT_DB:=l1_copilot}"
# Ver seed-l1-copilot-db.sh: forzar UTF8 evita que psql nativo de Windows
# reinterprete el SQL con el codepage ANSI del sistema.
PGCLIENTENCODING=UTF8
export PGHOST PGPORT PGUSER PGPASSWORD PGCLIENTENCODING

SCHEMA_FILE="backend/src/main/resources/schema-postgresql.sql"
SEED_SCRIPT="$(dirname "$0")/seed-l1-copilot-db.sh"

NO_SEED=false
for arg in "$@"; do
  case "$arg" in
    --no-seed) NO_SEED=true ;;
    -h|--help)
      echo "Uso: $0 [--no-seed]"
      echo "  Crea la BD $PROJECT_DB, aplica el esquema y carga el seed."
      echo "  --no-seed   No ejecuta seed-l1-copilot-db.sh al final."
      exit 0
      ;;
    *) echo "Argumento no reconocido: $arg" >&2; exit 1 ;;
  esac
done

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# -----------------------------------------------------------------------------
# 0. Requisitos
# -----------------------------------------------------------------------------
step "Verificando requisitos..."
command -v psql >/dev/null 2>&1 || fail "psql no encontrado en el PATH."
[ -f "$SCHEMA_FILE" ] || fail "No se encuentra $SCHEMA_FILE (¿estás en la raíz del repo?)."

if ! psql -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
  fail "No se puede conectar a $PGHOST:$PGPORT como $PGUSER. ¿Está Rancher Desktop arrancado y el NodePort 15432 expuesto?"
fi
ok "Conexión a Postgres OK ($PGHOST:$PGPORT como $PGUSER)"

# -----------------------------------------------------------------------------
# 1. Crear la BD del proyecto (idempotente)
# -----------------------------------------------------------------------------
step "Creando BD '$PROJECT_DB' (si no existe)..."

DB_EXISTS=$(psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$PROJECT_DB';")

if [ "$DB_EXISTS" = "1" ]; then
  warn "BD '$PROJECT_DB' ya existe."
else
  psql -d postgres -c "CREATE DATABASE $PROJECT_DB OWNER $PGUSER;"
  ok "BD '$PROJECT_DB' creada"
fi

# -----------------------------------------------------------------------------
# 2. Aplicar schema (idempotente con CREATE TABLE IF NOT EXISTS)
# -----------------------------------------------------------------------------
step "Aplicando $SCHEMA_FILE..."
psql -d "$PROJECT_DB" -v ON_ERROR_STOP=1 -f "$SCHEMA_FILE"
ok "Schema aplicado"

# -----------------------------------------------------------------------------
# 3. Seed
# -----------------------------------------------------------------------------
if $NO_SEED; then
  warn "Seed omitido (--no-seed). Para cargarlo más tarde: bash $SEED_SCRIPT"
else
  step "Cargando seed con $SEED_SCRIPT"
  PGDATABASE="$PROJECT_DB" bash "$SEED_SCRIPT"
fi

cat <<EOF

============================================================
  BD del L1 Copilot lista
============================================================
  Host externo (host)      : $PGHOST:$PGPORT
  Host interno (en-cluster): postgres.infra.svc.cluster.local:5432
  Base de datos            : $PROJECT_DB
  Usuario                  : $PGUSER
------------------------------------------------------------
  JDBC URL para el backend (perfil prod):
  jdbc:postgresql://postgres.infra.svc.cluster.local:5432/$PROJECT_DB
============================================================
EOF
