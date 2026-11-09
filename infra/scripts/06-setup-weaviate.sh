#!/usr/bin/env bash
# =============================================================================
# 06-setup-weaviate.sh
# Deploys a SHARED Weaviate in the 'infra' namespace of k3s.
#
# Same approach as 03-setup-postgres.sh: ONE instance for the whole portfolio;
# each project creates its own class (AutoOps -> AutoopsDocChunk). No auth (local)
# and no vectorizer (apps bring their own vectors).
#
# Features:
#   - Image: cr.weaviate.io/semitechnologies/weaviate:1.27.0
#   - Persistence: 5 Gi PVC
#   - Internal access: ClusterIP weaviate.infra.svc.cluster.local (8080 HTTP / 50051 gRPC)
#   - External access (debug): kubectl port-forward (not exposed by default)
#
# Requires Rancher Desktop / k3s running and kubectl.
# =============================================================================

set -euo pipefail

NS="infra"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPONENT_DIR="${SCRIPT_DIR}/../components/weaviate"
MANIFEST="${COMPONENT_DIR}/weaviate.yaml"
RERANKER_MANIFEST="${COMPONENT_DIR}/reranker.yaml"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =============================================================================
# 0. Requirements
# =============================================================================
step "Checking requirements..."
command -v kubectl &>/dev/null || fail "'kubectl' not found on PATH."
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
[[ -f "$MANIFEST" ]] || fail "Manifest not found: $MANIFEST"
ok "kubectl and k3s available"

# =============================================================================
# 1. Namespace (shared with Postgres)
# =============================================================================
step "Ensuring namespace '$NS'..."
if kubectl get namespace "$NS" &>/dev/null; then
  ok "Namespace '$NS' already exists"
else
  kubectl create namespace "$NS"
  ok "Namespace '$NS' created"
fi

# =============================================================================
# 2. Apply Weaviate (PVC + StatefulSet + Service)
# =============================================================================
step "Applying reranker-transformers (used by the AutoOps RAG)..."
kubectl apply -f "$RERANKER_MANIFEST"
ok "Reranker applied"

step "Applying Weaviate (idempotent)..."
kubectl apply -f "$MANIFEST"
ok "Manifest applied"

# =============================================================================
# 3. Wait until Ready
# =============================================================================
step "Waiting for the reranker (downloads the model on first run, max. 300 s)..."
kubectl rollout status deployment/weaviate-reranker -n "$NS" --timeout=300s || \
  warn "The reranker is not ready yet; Weaviate starts anyway and will retry."

step "Waiting for Weaviate to be ready (max. 180 s)..."
kubectl rollout status statefulset/weaviate -n "$NS" --timeout=180s
ok "Weaviate Ready"

# =============================================================================
# 4. Check the /ready endpoint
# =============================================================================
step "Checking /v1/.well-known/ready inside the pod..."
if kubectl exec -n "$NS" weaviate-0 -- wget -qO- http://localhost:8080/v1/.well-known/ready &>/dev/null; then
  ok "Ready endpoint answers"
else
  warn "The endpoint does not answer yet; wait a few seconds and retry."
fi

# =============================================================================
# 5. Summary
# =============================================================================
echo ""
echo "============================================================"
echo "  Shared Weaviate deployed to namespace '$NS'"
echo "============================================================"
echo "  Internal host (in-cluster) : weaviate.${NS}.svc.cluster.local"
echo "  HTTP / gRPC port           : 8080 / 50051"
echo "  Vectorizer                 : none (each app brings its vectors)"
echo "  Auth                       : anonymous (local only)"
echo "------------------------------------------------------------"
echo "  Each project creates ITS OWN class (indexes are not shared):"
echo "    AutoOps -> AutoopsDocChunk (created by the app on startup)"
echo "------------------------------------------------------------"
echo "  Debug from the host (port-forward):"
echo "    kubectl port-forward svc/weaviate 8088:8080 -n ${NS}"
echo "    http://localhost:8088/v1/schema"
echo "============================================================"
