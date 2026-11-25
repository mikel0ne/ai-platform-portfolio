#!/usr/bin/env bash
# =============================================================================
# 09-setup-langfuse.sh
# Installs self-hosted Langfuse v4 in the 'langfuse' namespace of k3s, with ALL of
# its datastores deployed by the chart itself (Postgres, ClickHouse + Keeper, Valkey
# and SeaweedFS as S3). It does not use the shared Postgres of 'infra':
# `helm uninstall langfuse -n langfuse` resets it without touching the app databases.
#
# Uses the langfuse/langfuse chart (https://langfuse.github.io/langfuse-k8s) with
# infra/components/langfuse/langfuse-values.yaml. Exposes the UI through Traefik at
# http://langfuse.local.
#
# Cluster prerequisites (installed by this script, once, idempotent):
#   - cert-manager         (the ClickHouse operator creates Certificates)
#   - clickhouse-operator  (the chart renders ClickHouseCluster/KeeperCluster)
#
# Secrets: generated with openssl the first time and stored in infra/.env.local
# (gitignored); reused on re-runs so sessions and encrypted data stay valid. They
# are never printed: only key names.
#
# Requires Rancher Desktop / k3s (>= 1.28) running, kubectl, helm (>= 3.17), openssl.
# =============================================================================

set -euo pipefail

NS="langfuse"
RELEASE="langfuse"          # the chart requires release "langfuse" (Redis hostname)
CHART_VERSION="2.2.6"       # chart langfuse/langfuse
APP_VERSION="4.83.0"        # chart appVersion (also pinned in the values)
CERT_MANAGER_VERSION="v1.20.2"
CH_OPERATOR_VERSION="0.0.8"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPONENT_DIR="${SCRIPT_DIR}/../components/langfuse"
VALUES="${COMPONENT_DIR}/langfuse-values.yaml"
INGRESS="${COMPONENT_DIR}/langfuse-ingress.yaml"
ENV_LOCAL="${ENV_LOCAL:-${SCRIPT_DIR}/../.env.local}"
HOST="langfuse.local"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# Reads KEY from .env.local (no 'source': nothing is executed and the value stays out of the environment).
env_get() { grep -E "^$1=" "$ENV_LOCAL" 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '\r' || true; }

# Generates KEY with the given command and appends it to .env.local if missing. Prints only the name.
ensure_secret() {
  local key="$1"; shift
  if [[ -n "$(env_get "$key")" ]]; then
    ok "$key: reused from $(basename "$ENV_LOCAL")"
  else
    if [[ -s "$ENV_LOCAL" && -n "$(tail -c1 "$ENV_LOCAL")" ]]; then echo "" >> "$ENV_LOCAL"; fi
    printf '%s=%s\n' "$key" "$("$@")" >> "$ENV_LOCAL"
    ok "$key: generated and saved to $(basename "$ENV_LOCAL")"
  fi
}
rand_b64()  { openssl rand -base64 32 | tr -d '\n'; }
rand_hex()  { openssl rand -hex 32; }
rand_pass() { openssl rand -base64 24 | tr -d '/+=\n'; }
rand_pk()   { echo "pk-lf-$(openssl rand -hex 16)"; }
rand_sk()   { echo "sk-lf-$(openssl rand -hex 16)"; }

# =============================================================================
# 0. Requirements
# =============================================================================
step "Checking requirements..."
command -v kubectl &>/dev/null || fail "'kubectl' not found on PATH."
command -v helm &>/dev/null    || fail "'helm' not found on PATH."
command -v openssl &>/dev/null || fail "'openssl' not found on PATH."
kubectl cluster-info &>/dev/null || fail "k3s is not responding. Open Rancher Desktop."
[[ -f "$VALUES" ]]  || fail "Values file not found: $VALUES"
[[ -f "$INGRESS" ]] || fail "Ingress manifest not found: $INGRESS"
[[ -f "$ENV_LOCAL" ]] || fail "$ENV_LOCAL not found (copy infra/.env.local.example)."
K8S_MINOR="$(kubectl version -o json 2>/dev/null | grep -A3 '"serverVersion"' | grep '"minor"' | tr -dc '0-9' || true)"
[[ -n "$K8S_MINOR" && "$K8S_MINOR" -ge 28 ]] || fail "Kubernetes >= 1.28 is required by the ClickHouse operator."
ok "kubectl, helm, openssl and k3s (1.${K8S_MINOR}) available"

# =============================================================================
# 1. Cluster prerequisites: cert-manager + clickhouse-operator
# =============================================================================
step "cert-manager ${CERT_MANAGER_VERSION}..."
if helm status cert-manager -n cert-manager &>/dev/null; then
  ok "cert-manager already installed"
else
  helm install cert-manager oci://quay.io/jetstack/charts/cert-manager \
    --version "$CERT_MANAGER_VERSION" \
    --namespace cert-manager --create-namespace \
    --set crds.enabled=true --wait --timeout 5m
  ok "cert-manager installed"
fi
kubectl wait --for=condition=Established \
  crd/certificates.cert-manager.io crd/issuers.cert-manager.io --timeout=120s >/dev/null
ok "cert-manager CRDs ready"

step "clickhouse-operator ${CH_OPERATOR_VERSION}..."
if helm status clickhouse-operator -n clickhouse-operator &>/dev/null; then
  ok "clickhouse-operator already installed"
else
  helm install clickhouse-operator oci://ghcr.io/clickhouse/clickhouse-operator-helm \
    --version "$CH_OPERATOR_VERSION" \
    --namespace clickhouse-operator --create-namespace --wait --timeout 5m
  ok "clickhouse-operator installed"
fi
kubectl wait --for=condition=Established \
  crd/clickhouseclusters.clickhouse.com crd/keeperclusters.clickhouse.com --timeout=120s >/dev/null
ok "ClickHouse CRDs ready"

# =============================================================================
# 2. Namespace
# =============================================================================
step "Ensuring namespace '$NS'..."
kubectl get namespace "$NS" &>/dev/null && ok "Namespace '$NS' already exists" || {
  kubectl create namespace "$NS"; ok "Namespace '$NS' created"
}

# =============================================================================
# 3. Secrets (infra/.env.local -> Secret 'langfuse')
# =============================================================================
step "Langfuse secrets in $(basename "$ENV_LOCAL")..."
ensure_secret LANGFUSE_NEXTAUTH_SECRET     rand_b64
ensure_secret LANGFUSE_SALT                rand_b64
ensure_secret LANGFUSE_ENCRYPTION_KEY      rand_hex
ensure_secret LANGFUSE_POSTGRES_PASSWORD   rand_pass
ensure_secret LANGFUSE_CLICKHOUSE_PASSWORD rand_pass
ensure_secret LANGFUSE_REDIS_PASSWORD      rand_pass
ensure_secret LANGFUSE_S3_PASSWORD         rand_pass
ensure_secret LANGFUSE_ADMIN_PASSWORD      rand_pass
ensure_secret LANGFUSE_AUTOOPS_PUBLIC_KEY  rand_pk
ensure_secret LANGFUSE_AUTOOPS_SECRET_KEY  rand_sk

step "Applying Secret '$NS/langfuse' (idempotent)..."
S3_USER="langfuse"
PG="$(env_get LANGFUSE_POSTGRES_PASSWORD)"
S3PW="$(env_get LANGFUSE_S3_PASSWORD)"
# Through stdin (heredoc): the values never go through arguments or shell history.
kubectl apply -n "$NS" -f - >/dev/null <<YAML
apiVersion: v1
kind: Secret
type: Opaque
metadata:
  name: langfuse
  labels:
    app.kubernetes.io/part-of: langfuse
stringData:
  salt: "$(env_get LANGFUSE_SALT)"
  encryption-key: "$(env_get LANGFUSE_ENCRYPTION_KEY)"
  nextauth-secret: "$(env_get LANGFUSE_NEXTAUTH_SECRET)"
  postgresql-password: "${PG}"
  POSTGRES_USER: "postgres"
  POSTGRES_PASSWORD: "${PG}"
  POSTGRES_DB: "langfuse"
  USERDB_USER: "langfuse"
  USERDB_PASSWORD: "${PG}"
  clickhouse-password: "$(env_get LANGFUSE_CLICKHOUSE_PASSWORD)"
  redis-password: "$(env_get LANGFUSE_REDIS_PASSWORD)"
  default: "$(env_get LANGFUSE_REDIS_PASSWORD)"
  s3-user: "${S3_USER}"
  s3-password: "${S3PW}"
  seaweedfs_s3_config: |
    {"identities":[{"name":"${S3_USER}","credentials":[{"accessKey":"${S3_USER}","secretKey":"${S3PW}"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
  init-user-password: "$(env_get LANGFUSE_ADMIN_PASSWORD)"
  init-project-public-key: "$(env_get LANGFUSE_AUTOOPS_PUBLIC_KEY)"
  init-project-secret-key: "$(env_get LANGFUSE_AUTOOPS_SECRET_KEY)"
YAML
unset PG S3PW
ok "Secret 'langfuse' applied (keys: salt, encryption-key, nextauth-secret, *-password, init-*)"

# =============================================================================
# 4. Langfuse Helm repo
# =============================================================================
step "Adding the Langfuse Helm repo..."
helm repo add langfuse https://langfuse.github.io/langfuse-k8s >/dev/null 2>&1 || true
helm repo update langfuse >/dev/null
ok "langfuse repo updated"

# =============================================================================
# 5. Install / upgrade Langfuse
# =============================================================================
step "Installing Langfuse chart ${CHART_VERSION} (app ${APP_VERSION})..."
helm upgrade --install "$RELEASE" langfuse/langfuse \
  --version "$CHART_VERSION" \
  --namespace "$NS" \
  -f "$VALUES" \
  --set langfuse.image.tag="$APP_VERSION" \
  --wait --timeout 15m
ok "Langfuse deployed"

# =============================================================================
# 6. Ingress (Traefik)
# =============================================================================
step "Applying the Langfuse Ingress (${HOST})..."
kubectl apply -f "$INGRESS"
ok "Ingress 'langfuse' applied"

# =============================================================================
# 7. Wait until Ready
# =============================================================================
step "Waiting for the Deployments (max 600 s)..."
kubectl rollout status deployment/langfuse-web -n "$NS" --timeout=600s
kubectl rollout status deployment/langfuse-worker -n "$NS" --timeout=600s
ok "langfuse-web and langfuse-worker Ready"

step "Checking /api/public/health inside the web pod..."
if kubectl exec -n "$NS" deploy/langfuse-web -- wget -qO- http://localhost:3000/api/public/health &>/dev/null; then
  ok "Health endpoint responds"
else
  warn "The endpoint is not responding yet; wait a few seconds and retry."
fi

# =============================================================================
# 8. Status
# =============================================================================
step "Pods in '$NS':"
kubectl get pods -n "$NS"

if [[ -z "$(env_get LANGFUSE_L1COPILOT_PUBLIC_KEY)" || -z "$(env_get LANGFUSE_L1COPILOT_SECRET_KEY)" ]]; then
  warn "LANGFUSE_L1COPILOT_PUBLIC_KEY / _SECRET_KEY missing in $(basename "$ENV_LOCAL") (manual project, see INFRA.md)."
fi

# =============================================================================
# 9. Summary
# =============================================================================
echo ""
echo "============================================================"
echo "  Langfuse ${APP_VERSION} (chart ${CHART_VERSION}) deployed in namespace '$NS'"
echo "============================================================"
echo "  UI                   : http://${HOST}"
echo "  Login (headless init): admin@langfuse.local / password in LANGFUSE_ADMIN_PASSWORD (.env.local)"
echo "  Internal host (apps) : langfuse-web.${NS}.svc.cluster.local:3000"
echo "  OTLP endpoint        : http://langfuse-web.${NS}.svc.cluster.local:3000/api/public/otel"
echo "                         (HTTP/protobuf, e.g. /v1/traces; Basic auth with the project pk:sk)"
echo "  Datastores (chart)   : Postgres, ClickHouse+Keeper, Valkey, SeaweedFS (S3)"
echo "------------------------------------------------------------"
echo "  API keys in infra/.env.local (names only):"
echo "    autoops    -> LANGFUSE_AUTOOPS_PUBLIC_KEY / LANGFUSE_AUTOOPS_SECRET_KEY    (seeded)"
echo "    l1-copilot -> LANGFUSE_L1COPILOT_PUBLIC_KEY / LANGFUSE_L1COPILOT_SECRET_KEY (manual, see INFRA.md)"
echo "------------------------------------------------------------"
echo "  Resolve the host (once, as Admin):"
echo "    infra/scripts/add-host.ps1 -Hostname ${HOST}"
echo "------------------------------------------------------------"
echo "  Debug from the host (port-forward):"
echo "    kubectl port-forward svc/langfuse-web 3000:3000 -n ${NS}"
echo "    http://localhost:3000"
echo "  Logs:"
echo "    kubectl logs -n ${NS} deploy/langfuse-web -f --tail=50"
echo "    kubectl logs -n ${NS} deploy/langfuse-worker -f --tail=50"
echo "  Full reset (deletes the traces):"
echo "    helm uninstall ${RELEASE} -n ${NS}; kubectl delete pvc --all -n ${NS}"
echo "============================================================"
