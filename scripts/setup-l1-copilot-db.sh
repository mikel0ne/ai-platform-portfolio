#!/usr/bin/env bash
# =============================================================================
# setup-l1-copilot-db.sh
# Crea la BD `l1_copilot` dentro del PostgreSQL compartido (namespace `infra`)
# y aplica el esquema + seed del proyecto L1 Support Copilot.
#
# Modelo: una BD por proyecto, todas en el mismo motor Postgres del clúster.
# Ver: infra/INFRA.md (Postgres compartido) y memoria project-postgres-compartido.
#
# Requisitos:
#   - infra/scripts/03-setup-postgres.sh ejecutado previamente
#   - kubectl en el PATH y apuntando al clúster de Rancher Desktop
#   - Estar ejecutando desde la raíz del repo (usa rutas relativas)
#
# Idempotente: si la BD ya existe, no la recrea; el esquema usa
# CREATE TABLE IF NOT EXISTS. El seed por defecto NO se reaplica
# (pasa --reseed para forzar recarga del seed).
# =============================================================================

set -euo pipefail

NS="infra"
PG_DEPLOY="postgres"
ADMIN_USER="appuser"          # superuser creado por 03-setup-postgres.sh
PROJECT_DB="l1_copilot"
SCHEMA_FILE="backend/src/main/resources/schema-postgresql.sql"
SEED_FILE="backend/src/main/resources/data-postgresql.sql"

RESEED=false
for arg in "$@"; do
  case "$arg" in
    --reseed) RESEED=true ;;
    -h|--help)
      echo "Uso: $0 [--reseed]"
      echo "  --reseed   Reaplica $SEED_FILE aunque la BD ya existiera (idempotente: usa TRUNCATE + INSERT)"
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
command -v kubectl &>/dev/null || fail "kubectl no encontrado en el PATH."
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
[ -f "$SCHEMA_FILE" ] || fail "No se encuentra $SCHEMA_FILE (¿estás en la raíz del repo?)."
[ -f "$SEED_FILE" ]   || fail "No se encuentra $SEED_FILE (¿estás en la raíz del repo?)."

PG_POD=$(kubectl get pod -n "$NS" -l app=postgres -o jsonpath="{.items[0].metadata.name}" 2>/dev/null || true)
[ -n "$PG_POD" ] || fail "No hay pod de Postgres en namespace '$NS'. Ejecuta infra/scripts/03-setup-postgres.sh."
ok "Postgres encontrado: $PG_POD"

# -----------------------------------------------------------------------------
# 1. Crear la BD del proyecto (idempotente)
# -----------------------------------------------------------------------------
step "Creando BD '$PROJECT_DB' (si no existe)..."

DB_EXISTS=$(kubectl exec -n "$NS" "$PG_POD" -- \
  psql -U "$ADMIN_USER" -d postgres -tAc \
  "SELECT 1 FROM pg_database WHERE datname='$PROJECT_DB';" 2>/dev/null || true)

if [ "$DB_EXISTS" = "1" ]; then
  warn "BD '$PROJECT_DB' ya existe."
  DB_WAS_NEW=false
else
  kubectl exec -n "$NS" "$PG_POD" -- \
    psql -U "$ADMIN_USER" -d postgres -c "CREATE DATABASE $PROJECT_DB OWNER $ADMIN_USER;"
  ok "BD '$PROJECT_DB' creada"
  DB_WAS_NEW=true
fi

# -----------------------------------------------------------------------------
# 2. Aplicar schema (idempotente con CREATE TABLE IF NOT EXISTS)
# -----------------------------------------------------------------------------
step "Aplicando $SCHEMA_FILE..."
kubectl exec -i -n "$NS" "$PG_POD" -- \
  psql -U "$ADMIN_USER" -d "$PROJECT_DB" -v ON_ERROR_STOP=1 < "$SCHEMA_FILE"
ok "Schema aplicado"

# -----------------------------------------------------------------------------
# 3. Seed: solo en primer arranque o si se fuerza con --reseed
# -----------------------------------------------------------------------------
if $DB_WAS_NEW || $RESEED; then
  step "Aplicando $SEED_FILE..."
  kubectl exec -i -n "$NS" "$PG_POD" -- \
    psql -U "$ADMIN_USER" -d "$PROJECT_DB" -v ON_ERROR_STOP=1 < "$SEED_FILE"
  ok "Seed aplicado"
else
  warn "Seed omitido (la BD ya existía). Para reaplicarlo: $0 --reseed"
fi

# -----------------------------------------------------------------------------
# 4. Resumen
# -----------------------------------------------------------------------------
ROW_COUNT=$(kubectl exec -n "$NS" "$PG_POD" -- \
  psql -U "$ADMIN_USER" -d "$PROJECT_DB" -tAc "SELECT COUNT(*) FROM orders;" 2>/dev/null || echo "?")

cat <<EOF

============================================================
  BD del L1 Copilot lista
============================================================
  Host interno (en-cluster): postgres.${NS}.svc.cluster.local:5432
  Base de datos            : ${PROJECT_DB}
  Usuario                  : ${ADMIN_USER}
  Filas en 'orders'        : ${ROW_COUNT}
------------------------------------------------------------
  JDBC URL para el backend:
  jdbc:postgresql://postgres.${NS}.svc.cluster.local:5432/${PROJECT_DB}
============================================================
EOF
