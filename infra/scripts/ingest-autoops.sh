#!/usr/bin/env bash
# =============================================================================
# ingest-autoops.sh — dispara la ingesta del corpus de AutoOps en Weaviate.
# =============================================================================
# El endpoint /admin/ingest NO se expone por el Ingress (operación interna): se
# alcanza con un port-forward temporal al Service del backend.
#
# Uso:
#   ./infra/scripts/ingest-autoops.sh            # ingesta incremental
#   ./infra/scripts/ingest-autoops.sh --reset    # borra y reindexa (obligatorio
#                                                  # si cambias el modelo de embeddings)
#
# Requiere: backend autoops desplegado y Ready; kubectl y curl en PATH.
# =============================================================================
set -euo pipefail

NS="autoops"
SVC="autoops-backend-generic-app"
LOCAL_PORT="18080"
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

step "Lanzando ingesta (reset=${RESET})..."
curl -fsS -X POST "http://localhost:${LOCAL_PORT}/admin/ingest?reset=${RESET}" \
  -H 'Content-Type: application/json' \
  && echo
ok "Ingesta solicitada"

step "Verificando objetos en la clase AutoopsDocChunk (Weaviate compartido)..."
kubectl port-forward svc/weaviate "18088:8080" -n infra >/dev/null 2>&1 &
WPF_PID=$!
sleep 3
curl -fsS "http://localhost:18088/v1/objects?class=AutoopsDocChunk&limit=1" | head -c 400 || true
echo
kill "$WPF_PID" 2>/dev/null || true
ok "Hecho"
