#!/usr/bin/env bash
# =============================================================================
# setup-app-ingress.sh: generic Traefik Ingress for one project of the monorepo
# =============================================================================
# Generic script. Reads infra/projects/<project>.env and creates ONE Ingress that
# routes by path to the project's backend and frontend:
#
#   <INGRESS_BACKEND_PATHS...>  -> <project>-backend-generic-app
#   /                           -> <project>-frontend-generic-app
#
# Traefik already exposes the HTTP entrypoint "web" on localhost:80 (k3s /
# Rancher Desktop), so neither daemon.json nor the Traefik config are touched.
# Traefik does not buffer responses by default, so MCP streaming (/mcp) works
# without extra configuration.
#
# Usage:
#   ./infra/scripts/setup-app-ingress.sh --project autoops
#   ./infra/scripts/setup-app-ingress.sh --project l1-copilot
#
# The host (e.g. autoops.local) resolves to 127.0.0.1 through the Windows hosts
# file: infra/scripts/add-<project>-host.ps1 (as Administrator, once).
# =============================================================================
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# ── Arguments ────────────────────────────────────────────────────────────────
PROJECT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --project) PROJECT="${2:?--project needs a value}"; shift 2 ;;
    -h|--help) sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "Unknown flag: $1" ;;
  esac
done
[[ -n "${PROJECT}" ]] || fail "--project <name> is required"

readonly PROJECT_ENV="${INFRA_DIR}/projects/${PROJECT}.env"
[[ -f "${PROJECT_ENV}" ]] || fail "Descriptor ${PROJECT_ENV} not found"
# shellcheck disable=SC1090
source "${PROJECT_ENV}"

: "${NAMESPACE:?The descriptor must define NAMESPACE}"
: "${INGRESS_HOST:?The descriptor must define INGRESS_HOST}"
BACKEND_PATHS="${INGRESS_BACKEND_PATHS:-/api}"

# The generic-app chart names its Services <release>-generic-app.
readonly BACKEND_SVC="${PROJECT}-backend-generic-app"
readonly FRONTEND_SVC="${PROJECT}-frontend-generic-app"

# ── Requirements ─────────────────────────────────────────────────────────────
step "Checking requirements (${PROJECT})..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."
kubectl get namespace "$NAMESPACE" &>/dev/null || \
  fail "Namespace '$NAMESPACE' does not exist. Deploy first (deploy.sh)."
kubectl get svc "$BACKEND_SVC" -n "$NAMESPACE" &>/dev/null || \
  fail "Service '$BACKEND_SVC' not found. Deploy the backend first."
kubectl get svc "$FRONTEND_SVC" -n "$NAMESPACE" &>/dev/null || \
  warn "Service '$FRONTEND_SVC' not found. '/' has no backend until the frontend is deployed."
ok "Requirements OK"

# ── Backend path rules ───────────────────────────────────────────────────────
backend_paths_yaml() {
  local p
  for p in ${BACKEND_PATHS}; do
    cat <<EOF
          - path: ${p}
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
EOF
  done
}

# ── Ingress ──────────────────────────────────────────────────────────────────
step "Applying the Ingress for ${INGRESS_HOST}..."
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ${PROJECT}-app
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/part-of: ${PROJECT}
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: "${INGRESS_HOST}"
      http:
        paths:
$(backend_paths_yaml)
          # Frontend: everything else
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ${FRONTEND_SVC}
                port:
                  name: http
EOF
ok "Ingress '${PROJECT}-app' applied"

step "Ingress in namespace '${NAMESPACE}':"
kubectl get ingress -n "$NAMESPACE"

step "Testing connectivity (it can take a few seconds)..."
sleep 3
check_url() {
  if curl -sf --max-time 5 "$1" &>/dev/null; then ok "$1"; else warn "$1 — no answer yet"; fi
}
# The first backend path doubles as health probe
check_url "http://${INGRESS_HOST}/"

echo ""
echo "============================================================"
echo "  ${PROJECT} — access URLs"
echo "============================================================"
echo "  http://${INGRESS_HOST}/                 <- Frontend"
for p in ${BACKEND_PATHS}; do
  echo "  http://${INGRESS_HOST}${p}    <- Backend"
done
echo ""
echo "  If the host does not resolve: infra/scripts/add-host.ps1 -Hostname ${INGRESS_HOST} (Administrator)"
echo "============================================================"
