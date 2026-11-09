#!/usr/bin/env bash
# =============================================================================
# ingest-autoops.sh — dispara la ingesta del corpus de AutoOps en Weaviate.
# =============================================================================
# Los endpoints /admin/* NO se exponen por el Ingress (operación interna): se
# alcanzan con un port-forward temporal al Service del backend.
#
# Uso:
#   ./infra/scripts/ingest-autoops.sh            # ingesta incremental (local + repo Git)
#   ./infra/scripts/ingest-autoops.sh --reset    # vacía Weaviate y reindexa desde cero (obligatorio
#                                                  # si cambias el modelo de embeddings)
#
# El repo Git (monorepo) se ingesta por parámetro vía /admin/ingest/git, no por config: para
# ingestar otro repo a la wiki, repetir la llamada a ese endpoint con otra --url.
#
# Requiere: backend autoops desplegado y Ready; kubectl y curl en PATH.
# =============================================================================
set -euo pipefail

NS="autoops"
SVC="autoops-backend-generic-app"
LOCAL_PORT="18080"
GIT_REPO_URL="${GIT_REPO_URL:-https://github.com/mikel0ne/ai-platform-portfolio.git}"
ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.env.local"
if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
fi
: "${AUTOOPS_ADMIN_TOKEN:?Define AUTOOPS_ADMIN_TOKEN in infra/.env.local (see infra/.env.local.example)}"
RESET="false"
[[ "${1:-}" == "--reset" ]] && RESET="true"

step() { echo -e "\n==> $*"; }
ok()   { echo "    [ok] $*"; }
fail() { echo "    [x]  $*"; exit 1; }

command -v kubectl &>/dev/null || fail "'kubectl' no encontrado."
command -v curl &>/dev/null    || fail "'curl' no encontrado."
kubectl get svc "$SVC" -n "$NS" &>/dev/null || fail "Servicio '$SVC' no encontrado. Despliega el backend primero."

step "Abriendo port-forward a ${SVC} (localhost:${LOCAL_PORT})..."
kubectl port-forward "svc/${SVC}" "${LOCAL_PORT}:8080" -n "$NS" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT
sleep 3

if [[ "$RESET" == "true" ]]; then
  step "Vaciando la base vectorial..."
  curl -fsS -X POST -H "X-Admin-Token: ${AUTOOPS_ADMIN_TOKEN}" "http://localhost:${LOCAL_PORT}/admin/vectorstore/drop" && echo
  ok "Base vectorial vaciada"
fi

step "Ingestando repo Git (${GIT_REPO_URL}, rama por defecto)..."
curl -fsS -X POST -H "X-Admin-Token: ${AUTOOPS_ADMIN_TOKEN}" "http://localhost:${LOCAL_PORT}/admin/ingest/git?url=${GIT_REPO_URL}" \
  -H 'Content-Type: application/json' \
  && echo
ok "Repo Git ingestado"

step "Verificando objetos en la clase AutoopsDocChunk (Weaviate compartido)..."
kubectl port-forward svc/weaviate "18088:8080" -n infra >/dev/null 2>&1 &
WPF_PID=$!
sleep 3
curl -fsS "http://localhost:18088/v1/objects?class=AutoopsDocChunk&limit=1" | head -c 400 || true
echo
kill "$WPF_PID" 2>/dev/null || true
ok "Hecho"
