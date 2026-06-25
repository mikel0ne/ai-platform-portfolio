#!/usr/bin/env bash
# =============================================================================
# 06-setup-weaviate.sh
# Despliega Weaviate COMPARTIDO en el namespace 'infra' de k3s.
#
# Mismo criterio que 03-setup-postgres.sh: UNA instancia para todo el portfolio;
# cada proyecto crea su propia clase (AutoOps → AutoopsDocChunk). Sin auth (local),
# sin vectorizador (las apps traen sus vectores).
#
# Características:
#   - Imagen: cr.weaviate.io/semitechnologies/weaviate:1.27.0
#   - Persistencia: PVC de 5 Gi
#   - Acceso interno: ClusterIP  weaviate.infra.svc.cluster.local (8080 HTTP / 50051 gRPC)
#   - Acceso externo (debug): kubectl port-forward (no se expone por defecto)
#
# Requisitos: Rancher Desktop / k3s corriendo, kubectl disponible.
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
# 0. Requisitos
# =============================================================================
step "Verificando requisitos..."
command -v kubectl &>/dev/null || fail "'kubectl' no encontrado en el PATH."
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
[[ -f "$MANIFEST" ]] || fail "No encuentro el manifiesto: $MANIFEST"
ok "kubectl y k3s disponibles"

# =============================================================================
# 1. Namespace (compartido con Postgres)
# =============================================================================
step "Asegurando namespace '$NS'..."
if kubectl get namespace "$NS" &>/dev/null; then
  ok "Namespace '$NS' ya existe"
else
  kubectl create namespace "$NS"
  ok "Namespace '$NS' creado"
fi

# =============================================================================
# 2. Aplicar Weaviate (PVC + StatefulSet + Service)
# =============================================================================
step "Aplicando reranker-transformers (lo usa el RAG de AutoOps)..."
kubectl apply -f "$RERANKER_MANIFEST"
ok "Reranker aplicado"

step "Aplicando Weaviate (idempotente)..."
kubectl apply -f "$MANIFEST"
ok "Manifiesto aplicado"

# =============================================================================
# 3. Esperar a que esté Ready
# =============================================================================
step "Esperando al reranker (descarga el modelo la 1ª vez, máx. 300 s)..."
kubectl rollout status deployment/weaviate-reranker -n "$NS" --timeout=300s || \
  warn "El reranker aún no está listo; Weaviate arrancará igualmente y reintentará."

step "Esperando a que Weaviate esté listo (máx. 180 s)..."
kubectl rollout status statefulset/weaviate -n "$NS" --timeout=180s
ok "Weaviate Ready"

# =============================================================================
# 4. Verificación del endpoint /ready
# =============================================================================
step "Comprobando /v1/.well-known/ready dentro del pod..."
if kubectl exec -n "$NS" weaviate-0 -- wget -qO- http://localhost:8080/v1/.well-known/ready &>/dev/null; then
  ok "Endpoint ready responde"
else
  warn "El endpoint aún no responde; espera unos segundos y reintenta."
fi

# =============================================================================
# 5. Resumen
# =============================================================================
echo ""
echo "============================================================"
echo "  Weaviate (compartido) desplegado en namespace '$NS'"
echo "============================================================"
echo "  Host interno (en-cluster) : weaviate.${NS}.svc.cluster.local"
echo "  Puerto HTTP / gRPC        : 8080 / 50051"
echo "  Vectorizer                : none (cada app trae sus vectores)"
echo "  Auth                      : anónima (solo local)"
echo "------------------------------------------------------------"
echo "  Cada proyecto crea SU clase (no se comparte el índice):"
echo "    AutoOps → AutoopsDocChunk  (la crea la app al arrancar)"
echo "------------------------------------------------------------"
echo "  Debug desde el host (port-forward):"
echo "    kubectl port-forward svc/weaviate 8088:8080 -n ${NS}"
echo "    http://localhost:8088/v1/schema"
echo "============================================================"
