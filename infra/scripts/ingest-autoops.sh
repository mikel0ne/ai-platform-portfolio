#!/usr/bin/env bash
# =============================================================================
# ingest-autoops.sh: triggers the AutoOps RAG ingestion into Weaviate.
# =============================================================================
# The /admin/* endpoints are not exposed through the Ingress (internal operation):
# they are reached through a temporary port-forward and need AUTOOPS_ADMIN_TOKEN.
#
# Usage:
#   ./infra/scripts/ingest-autoops.sh            # incremental ingestion of the Git repo
#   ./infra/scripts/ingest-autoops.sh --reset    # empties Weaviate and reindexes from scratch (required
#                                                  # after changing the embedding model or the schema)
#
# The Git repo is passed as a parameter to /admin/ingest/git, not configured: to add
# another repo, call that endpoint again with another url (or set GIT_REPO_URL).
#
# Requires the autoops backend deployed and Ready; kubectl and curl on PATH.
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

command -v kubectl &>/dev/null || fail "'kubectl' not found."
command -v curl &>/dev/null    || fail "'curl' not found."
kubectl get svc "$SVC" -n "$NS" &>/dev/null || fail "Service '$SVC' not found. Deploy the backend first."

step "Opening a port-forward to ${SVC} (localhost:${LOCAL_PORT})..."
kubectl port-forward "svc/${SVC}" "${LOCAL_PORT}:8080" -n "$NS" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT
sleep 3

if [[ "$RESET" == "true" ]]; then
  step "Emptying the vector store..."
  curl -fsS -X POST -H "X-Admin-Token: ${AUTOOPS_ADMIN_TOKEN}" "http://localhost:${LOCAL_PORT}/admin/vectorstore/drop" && echo
  ok "Vector store emptied"
fi

step "Ingesting the Git repo (${GIT_REPO_URL}, default branch)..."
curl -fsS -X POST -H "X-Admin-Token: ${AUTOOPS_ADMIN_TOKEN}" "http://localhost:${LOCAL_PORT}/admin/ingest/git?url=${GIT_REPO_URL}" \
  -H 'Content-Type: application/json' \
  && echo
ok "Git repo ingested"

step "Checking objects in class AutoopsDocChunk (shared Weaviate)..."
kubectl port-forward svc/weaviate "18088:8080" -n infra >/dev/null 2>&1 &
WPF_PID=$!
sleep 3
curl -fsS "http://localhost:18088/v1/objects?class=AutoopsDocChunk&limit=1" | head -c 400 || true
echo
kill "$WPF_PID" 2>/dev/null || true
ok "Done"
