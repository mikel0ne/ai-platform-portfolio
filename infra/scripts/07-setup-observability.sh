#!/usr/bin/env bash
# =============================================================================
# 07-setup-observability.sh
# Installs the shared LOG stack (Loki + Promtail + Grafana) in the
# 'observability' namespace of k3s. It collects the logs of the whole cluster.
#
# Uses the grafana/loki-stack chart with infra/observability/loki-stack-values.yaml and
# exposes Grafana through Traefik at http://grafana.local (admin / GRAFANA_ADMIN_PASSWORD from infra/.env.local).
#
# Requires Rancher Desktop / k3s running, kubectl and helm.
# =============================================================================

set -euo pipefail

NS="observability"
RELEASE="loki"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OBS_DIR="${SCRIPT_DIR}/../observability"
VALUES="${OBS_DIR}/loki-stack-values.yaml"
ENV_FILE="$(cd "${OBS_DIR}/.." && pwd)/.env.local"
if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
fi
: "${GRAFANA_ADMIN_PASSWORD:?Define GRAFANA_ADMIN_PASSWORD in infra/.env.local (see infra/.env.local.example)}"
GRAFANA_INGRESS="${OBS_DIR}/grafana-ingress.yaml"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =============================================================================
# 0. Requirements
# =============================================================================
step "Checking requirements..."
command -v kubectl &>/dev/null || fail "'kubectl' not found."
command -v helm &>/dev/null    || fail "'helm' not found."
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
[[ -f "$VALUES" ]] || fail "No encuentro $VALUES"
ok "kubectl, helm and k3s available"

# =============================================================================
# 1. Namespace
# =============================================================================
step "Ensuring namespace '$NS'..."
kubectl get namespace "$NS" &>/dev/null && ok "Namespace '$NS' already exists" || {
  kubectl create namespace "$NS"; ok "Namespace '$NS' creado"
}

# =============================================================================
# 2. Grafana Helm repo
# =============================================================================
step "Adding the Grafana Helm repo..."
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update grafana >/dev/null
ok "grafana repo updated"

# =============================================================================
# 3. Install / upgrade loki-stack
# =============================================================================
step "Installing loki-stack (Loki + Promtail + Grafana)..."
# The password goes in a private values file: --set-string would show it in the process list.
admin_values="$(mktemp)"
chmod 600 "$admin_values"
backslash='\'
quote='"'
escaped_password="${GRAFANA_ADMIN_PASSWORD//"$backslash"/"$backslash$backslash"}"
escaped_password="${escaped_password//"$quote"/"$backslash$quote"}"
printf 'grafana:\n  adminPassword: "%s"\n' "$escaped_password" > "$admin_values"
helm upgrade --install "$RELEASE" grafana/loki-stack \
  --namespace "$NS" \
  -f "$VALUES" \
  -f "$admin_values" \
  --wait --timeout 5m
rm -f "$admin_values"
ok "loki-stack deployed"

# =============================================================================
# 4. Grafana Ingress (Traefik)
# =============================================================================
step "Applying the Grafana Ingress (grafana.local)..."
kubectl apply -f "$GRAFANA_INGRESS"
ok "Ingress 'grafana' applied"

# =============================================================================
# 5. Status
# =============================================================================
step "Pods in '$NS':"
kubectl get pods -n "$NS"

# =============================================================================
# 6. Summary
# =============================================================================
echo ""
echo "============================================================"
echo "  Observability (logs) deployed to namespace '$NS'"
echo "============================================================"
echo "  Grafana   : http://grafana.local   (admin / GRAFANA_ADMIN_PASSWORD)"
echo "  Loki      : loki.${NS}.svc.cluster.local:3100 (datasource already configured)"
echo "  Promtail  : DaemonSet, collects logs from ALL namespaces"
echo "------------------------------------------------------------"
echo "  Resolve the host (once, as Administrator):"
echo "    infra/scripts/add-host.ps1 -Hostname grafana.local"
echo "------------------------------------------------------------"
echo "  In Grafana: Explore -> Loki datasource. Sample queries:"
echo '    {namespace="autoops"}'
echo '    {namespace="autoops", pod=~"autoops-backend.*"} |= "ERROR"'
echo "============================================================"
