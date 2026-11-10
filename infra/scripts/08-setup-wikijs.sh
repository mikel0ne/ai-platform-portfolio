#!/usr/bin/env bash
# =============================================================================
# 08-setup-wikijs.sh
# Deploys Wiki.js to the 'wiki' namespace with Helm and the generic chart
# infra/charts/generic-app (not through deploy.sh: public image requarks/wiki:2,
# no build/push of its own, a single component; see infra/values-wikijs.yaml).
#
# Run wiki/scripts/setup-wiki-db.sh first: it creates the empty `wiki` database in
# the shared Postgres of the `infra` namespace; Wiki.js migrates its own schema
# on startup.
#
# Afterwards: open http://wiki.local, finish the setup wizard (admin user) and, in
# Admin Area -> API Access, create an API key with read:pages + read:source (the
# AutoOps backend reads pages for RAG ingestion) and write:pages (used by
# wiki/scripts/seed-wiki-content.sh to publish the content versioned in git).
#
# Requires Rancher Desktop / k3s running, kubectl and helm on PATH.
# =============================================================================

set -euo pipefail

NS="wiki"
INFRA_NS="infra"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHART_DIR="${INFRA_DIR}/charts/generic-app"
VALUES="${INFRA_DIR}/values-wikijs.yaml"
RELEASE="wikijs"
DEPLOYMENT="${RELEASE}-generic-app"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =============================================================================
# 0. Requirements
# =============================================================================
step "Checking requirements..."
command -v kubectl &>/dev/null || fail "'kubectl' not found on PATH."
command -v helm &>/dev/null || fail "'helm' not found on PATH."
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
[[ -f "$VALUES" ]] || fail "Values file not found: $VALUES"
kubectl get secret postgres-credentials -n "$INFRA_NS" &>/dev/null || \
  fail "Secret 'postgres-credentials' not found in '$INFRA_NS'. Run infra/scripts/03-setup-postgres.sh first."
ok "kubectl, helm and k3s available"

# =============================================================================
# 1. Namespace
# =============================================================================
step "Ensuring namespace '$NS'..."
kubectl apply -f "${INFRA_DIR}/namespace-wiki.yaml" >/dev/null
ok "Namespace '$NS' ready"

# =============================================================================
# 2. postgres-credentials Secret (copied from the infra namespace, same appuser)
# =============================================================================
step "Syncing Secret 'postgres-credentials' into '$NS' (from '$INFRA_NS')..."
# Copied as a manifest with the encoded data, so the values are never decoded onto a command line.
kubectl get secret postgres-credentials -n "$INFRA_NS" -o go-template="apiVersion: v1
kind: Secret
type: Opaque
metadata:
  name: postgres-credentials
  namespace: ${NS}
data:
  POSTGRES_USER: {{ .data.POSTGRES_USER }}
  POSTGRES_PASSWORD: {{ .data.POSTGRES_PASSWORD }}
" | kubectl apply -f - >/dev/null
ok "Secret synced"

# =============================================================================
# 3. Helm upgrade --install
# =============================================================================
step "helm upgrade --install ${RELEASE}..."
# --force-conflicts: Helm 4 uses server-side apply; needed after a manual kubectl
# edit of the same resource (same reasoning as deploy.sh).
helm upgrade --install "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NS}" \
  -f "${VALUES}" \
  --force-conflicts \
  --wait --timeout 3m
ok "Release '${RELEASE}' deployed"

# =============================================================================
# 4. Wait for the rollout and check /healthz
# =============================================================================
step "Waiting for the pod to be Ready (max. 120 s)..."
kubectl rollout status "deployment/${DEPLOYMENT}" -n "$NS" --timeout=120s
ok "Wiki.js Ready"

step "Checking /healthz inside the pod..."
WIKI_POD=$(kubectl get pod -n "$NS" -l "app.kubernetes.io/instance=${RELEASE}" -o jsonpath="{.items[0].metadata.name}")
if kubectl exec -n "$NS" "$WIKI_POD" -- node -e "require('http').get('http://localhost:3000/healthz', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))" &>/dev/null; then
  ok "/healthz endpoint answers"
else
  warn "/healthz did not return 200; if the container is still starting, retry in a few seconds."
fi

# =============================================================================
# 5. Summary
# =============================================================================
echo ""
echo "============================================================"
echo "  Wiki.js deployed to namespace '$NS'"
echo "============================================================"
echo "  Internal host (in-cluster) : ${DEPLOYMENT}.${NS}.svc.cluster.local:3000"
echo "  Database                   : wiki (shared Postgres, ns ${INFRA_NS})"
echo "------------------------------------------------------------"
echo "  Next steps:"
echo "  1. infra/scripts/add-wiki-host.ps1   (as Administrator, once)"
echo "  2. Open http://wiki.local and finish the setup wizard"
echo "  3. Admin Area -> API Access -> enable the API and create a key"
echo "     (permissions read:pages + read:source + write:pages)"
echo "  4. Store the key in infra/.env.local as WIKIJS_API_KEY=..."
echo "============================================================"
